package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 记忆索引的发件箱。写入与晋升在同一事务，投递失败不回滚已经生效的记忆。
 *
 * <p>B-07 多实例与旧写防护：
 * <ul>
 *   <li>入队时递增 memory_item.index_generation（UPSERT 与 DELETE 均递增），
 *       行携带 desired_generation，作为 ES external version 的单调来源；</li>
 *   <li>投递前以租约 CAS 认领，两个实例不会同时处理同一行；</li>
 *   <li>消费前重新读取条目当前权威内容与当前代次，不复用入队时冻结的正文；
 *       行代次已被更高代次取代时按 SUPERSEDED 跳过，不写旧数据；</li>
 *   <li>删除写入持久墓碑文档而非物理删除，迟到的旧 UPSERT 被版本拒绝。</li>
 * </ul>
 */
@Service
public class MemoryIndexOutbox {
    private static final Logger log = LoggerFactory.getLogger(MemoryIndexOutbox.class);
    private static final int BATCH = 20;
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_BACKOFF_SECONDS = 60;
    private static final long LEASE_SECONDS = 120;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MemoryVectorIndex vectorIndex;
    private final ObjectProvider<EmbeddingClient> embeddingClient;
    private final TransactionTemplate transactions;
    private final String instanceId = "memory-index-" + UUID.randomUUID();

    @org.springframework.beans.factory.annotation.Autowired
    public MemoryIndexOutbox(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MemoryVectorIndex vectorIndex,
            ObjectProvider<EmbeddingClient> embeddingClient,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.vectorIndex = vectorIndex;
        this.embeddingClient = embeddingClient;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** 兼容旧构造（测试）：无事务管理器时退化为直接执行。 */
    public MemoryIndexOutbox(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MemoryVectorIndex vectorIndex,
            ObjectProvider<EmbeddingClient> embeddingClient
    ) {
        this(jdbcTemplate, objectMapper, vectorIndex, embeddingClient, null);
    }

    public void enqueue(
            String workspaceId,
            String memoryItemId,
            String operation,
            String displayText,
            String slotKey,
            String taskNeighborhood
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("display_text", displayText == null ? "" : displayText);
        payload.put("slot_key", slotKey == null ? "" : slotKey);
        payload.put("task_neighborhood", taskNeighborhood == null ? "" : taskNeighborhood);
        // 入队与晋升同事务：UPSERT 与 DELETE 都递增条目级单调代次
        jdbcTemplate.update("update memory_item set index_generation = index_generation + 1 where id = ?",
                memoryItemId);
        Long generation = currentGeneration(memoryItemId);
        jdbcTemplate.update("""
                insert into memory_index_outbox(
                    id, workspace_id, memory_item_id, operation, payload_json, status, attempts,
                    desired_generation, created_at, updated_at
                ) values (?, ?, ?, ?, ?, 'PENDING', 0, ?, current_timestamp, current_timestamp)
                """,
                Ids.newId(), workspaceId, memoryItemId, operation, Json.write(objectMapper, payload),
                generation == null ? 1L : generation);
    }

    @Scheduled(fixedDelayString = "${noteweave.memory.index-dispatch-delay-ms:30000}")
    public void dispatchPending() {
        if (!vectorIndex.available()) {
            return;
        }
        List<Row> rows = jdbcTemplate.query("""
                select id, workspace_id, memory_item_id, operation, payload_json, attempts, desired_generation
                from memory_index_outbox
                where status in ('PENDING', 'PROCESSING')
                  and (next_attempt_at is null or next_attempt_at <= current_timestamp)
                  and (lease_until is null or lease_until < current_timestamp)
                order by created_at, id
                limit ?
                """, (rs, rowNum) -> new Row(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("memory_item_id"),
                rs.getString("operation"),
                rs.getString("payload_json"),
                rs.getInt("attempts"),
                rs.getLong("desired_generation")
        ), BATCH);
        for (Row row : rows) {
            dispatch(row);
        }
    }

    private void dispatch(Row row) {
        // 认领租约：CAS 防多实例重复投递；认领失败说明他人已持有
        int claimed = jdbcTemplate.update("""
                update memory_index_outbox
                set status = 'PROCESSING', lease_owner = ?, lease_until = timestampadd(second, ?, current_timestamp),
                    updated_at = current_timestamp
                where id = ? and status in ('PENDING', 'PROCESSING')
                  and (next_attempt_at is null or next_attempt_at <= current_timestamp)
                  and (lease_until is null or lease_until < current_timestamp)
                """, instanceId, LEASE_SECONDS, row.id());
        if (claimed != 1) {
            return;
        }
        try {
            // 消费前读取当前权威代次：行代次已被更高代次取代时按 SUPERSEDED 确认，不写旧数据。
            // current == null（条目行已不存在）同样按 SUPERSEDED 收敛，不写孤儿文档。
            // V199 旧行例外（v2 修订）：条目 index_generation=0 而行 desired 默认 1 时
            // 0 不大于 1、不应判陈旧——外部版本按下限 1 写入，绝不写 0 版本。
            Long current = currentGeneration(row.memoryItemId());
            if (current == null || current > row.desiredGeneration()) {
                markSent(row, "SUPERSEDED");
                return;
            }
            long generation = Math.max(1L, current);
            String error = "DELETE".equals(row.operation())
                    ? (vectorIndex.delete(row.workspaceId(), row.memoryItemId(), generation) ? null : "MEMORY_INDEX_WRITE_FAILED")
                    : upsert(row, generation);
            if (error == null) {
                markSent(row, null);
                return;
            }
            fail(row, error);
        } catch (Exception ex) {
            log.warn("记忆索引发件箱投递失败: {}", ex.toString());
            fail(row, ex.getMessage() == null ? "MEMORY_INDEX_WRITE_FAILED" : ex.getMessage());
        }
    }

    /** 成功返回 null，否则返回原因码。正文按消费时的权威内容重新读取。 */
    private String upsert(Row row, long generation) throws JsonProcessingException {
        EmbeddingClient embedding = embeddingClient.getIfAvailable();
        if (embedding == null || !embedding.isEnabled()) {
            return "MEMORY_EMBEDDING_UNAVAILABLE";
        }
        // 消费前读取当前权威内容：不复用入队时冻结的正文
        JsonNode payload = objectMapper.readTree(row.payloadJson() == null ? "{}" : row.payloadJson());
        String text = currentDisplayText(row.memoryItemId());
        if (text == null) {
            // 条目已不可读：按删除处理，写入墓碑
            return vectorIndex.delete(row.workspaceId(), row.memoryItemId(), generation)
                    ? null : "MEMORY_INDEX_WRITE_FAILED";
        }
        List<Float> vector = embedding.embedQuery(text).singleVector();
        boolean written = vectorIndex.upsert(new MemoryVectorIndex.Document(
                row.workspaceId(),
                row.memoryItemId(),
                payload.path("slot_key").asText(""),
                text,
                payload.path("task_neighborhood").asText(""),
                vector), generation);
        return written ? null : "MEMORY_INDEX_WRITE_FAILED";
    }

    /** 当前有效版本的正文；条目已删除或无有效版本时返回 null。 */
    private String currentDisplayText(String memoryItemId) {
        return jdbcTemplate.query("""
                select r.display_text
                from memory_item i
                left join memory_runtime_revision r on r.id = i.current_revision_id
                where i.id = ? and i.status not in ('DELETED', 'SUPERSEDED')
                """, rs -> rs.next() ? rs.getString(1) : null, memoryItemId);
    }

    private Long currentGeneration(String memoryItemId) {
        return jdbcTemplate.query("select index_generation from memory_item where id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, memoryItemId);
    }

    private void markSent(Row row, String note) {
        jdbcTemplate.update("""
                update memory_index_outbox
                set status = 'SENT', updated_at = current_timestamp, last_error = ?, lease_owner = null,
                    lease_until = null, next_attempt_at = null
                where id = ? and lease_owner = ?
                """, note, row.id(), instanceId);
    }

    private void fail(Row row, String error) {
        int attempts = row.attempts() + 1;
        boolean terminal = attempts >= MAX_ATTEMPTS;
        String message = error == null ? "" : error;
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        jdbcTemplate.update("""
                update memory_index_outbox
                set status = ?, attempts = ?, last_error = ?, updated_at = current_timestamp,
                    lease_owner = null, lease_until = null,
                    next_attempt_at = case when ? then null else timestampadd(second, ?, current_timestamp) end
                where id = ? and lease_owner = ?
                """, terminal ? "FAILED" : "PENDING", attempts, message, terminal,
                RETRY_BACKOFF_SECONDS, row.id(), instanceId);
    }

    /**
     * FB-08：投递视角指标与受控人工恢复。FAILED 行只在 desired_generation
     * 仍是条目当前权威代次时可重驱（复活旧代次写入由 ES external version 拒绝，
     * 这里直接在入口拦截）。无权限直连内部端点已由 InternalServiceAuthFilter 保护。
     */
    public Map<String, Long> metrics() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select status, count(*) as total,
                       coalesce(max(timestampdiff(second, created_at, current_timestamp)), 0) as oldest_age
                from memory_index_outbox group by status
                """, rs -> {
            counts.put("memory-index." + rs.getString("status") + ".count", rs.getLong("total"));
            counts.put("memory-index." + rs.getString("status") + ".oldest_age_seconds", rs.getLong("oldest_age"));
        });
        return counts;
    }

    /** 只重驱当前权威代次的 FAILED 行；被取代代次按陈旧拒绝。 */
    public boolean redriveFailed(String outboxId, String actor, String reason) {
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("MEMORY_INDEX_REDRIVE_IDENTITY_REQUIRED");
        }
        int moved = jdbcTemplate.update("""
                update memory_index_outbox
                set status = 'PENDING', attempts = 0, next_attempt_at = null,
                    last_error = ?, updated_at = current_timestamp
                where id = ? and status = 'FAILED'
                  and desired_generation = (select index_generation from memory_item where id = memory_index_outbox.memory_item_id)
                """, "redriven by " + actor + ": " + reason.substring(0, Math.min(400, reason.length())),
                outboxId);
        return moved == 1;
    }

    private record Row(
            String id,
            String workspaceId,
            String memoryItemId,
            String operation,
            String payloadJson,
            int attempts,
            long desiredGeneration
    ) {
    }
}
