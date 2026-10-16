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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 记忆索引的发件箱。写入与晋升在同一事务，投递失败不回滚已经生效的记忆。 */
@Service
public class MemoryIndexOutbox {
    private static final Logger log = LoggerFactory.getLogger(MemoryIndexOutbox.class);
    private static final int BATCH = 20;
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MemoryVectorIndex vectorIndex;
    private final ObjectProvider<EmbeddingClient> embeddingClient;

    public MemoryIndexOutbox(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MemoryVectorIndex vectorIndex,
            ObjectProvider<EmbeddingClient> embeddingClient
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.vectorIndex = vectorIndex;
        this.embeddingClient = embeddingClient;
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
        jdbcTemplate.update("""
                insert into memory_index_outbox(
                    id, workspace_id, memory_item_id, operation, payload_json, status, attempts
                ) values (?, ?, ?, ?, ?, 'PENDING', 0)
                """,
                Ids.newId(), workspaceId, memoryItemId, operation, Json.write(objectMapper, payload));
    }

    @Scheduled(fixedDelayString = "${noteweave.memory.index-dispatch-delay-ms:30000}")
    public void dispatchPending() {
        if (!vectorIndex.available()) {
            return;
        }
        List<Row> rows = jdbcTemplate.query("""
                select id, workspace_id, memory_item_id, operation, payload_json, attempts
                from memory_index_outbox
                where status = 'PENDING'
                order by created_at, id
                limit ?
                """, (rs, rowNum) -> new Row(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("memory_item_id"),
                rs.getString("operation"),
                rs.getString("payload_json"),
                rs.getInt("attempts")
        ), BATCH);
        for (Row row : rows) {
            dispatch(row);
        }
    }

    private void dispatch(Row row) {
        try {
            String error = "DELETE".equals(row.operation())
                    ? (vectorIndex.delete(row.workspaceId(), row.memoryItemId()) ? null : "MEMORY_INDEX_WRITE_FAILED")
                    : upsert(row);
            if (error == null) {
                jdbcTemplate.update("""
                        update memory_index_outbox
                        set status = 'SENT', updated_at = current_timestamp, last_error = null
                        where id = ? and status = 'PENDING'
                        """, row.id());
                return;
            }
            fail(row, error);
        } catch (Exception ex) {
            log.warn("记忆索引发件箱投递失败: {}", ex.toString());
            fail(row, ex.getMessage() == null ? "MEMORY_INDEX_WRITE_FAILED" : ex.getMessage());
        }
    }

    /** 成功返回 null，否则返回原因码。 */
    private String upsert(Row row) throws JsonProcessingException {
        EmbeddingClient embedding = embeddingClient.getIfAvailable();
        if (embedding == null || !embedding.isEnabled()) {
            return "MEMORY_EMBEDDING_UNAVAILABLE";
        }
        JsonNode payload = objectMapper.readTree(row.payloadJson() == null ? "{}" : row.payloadJson());
        String text = payload.path("display_text").asText("");
        List<Float> vector = embedding.embedQuery(text).singleVector();
        boolean written = vectorIndex.upsert(new MemoryVectorIndex.Document(
                row.workspaceId(),
                row.memoryItemId(),
                payload.path("slot_key").asText(""),
                text,
                payload.path("task_neighborhood").asText(""),
                vector));
        return written ? null : "MEMORY_INDEX_WRITE_FAILED";
    }

    private void fail(Row row, String error) {
        int attempts = row.attempts() + 1;
        String status = attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
        String message = error == null ? "" : error;
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        jdbcTemplate.update("""
                update memory_index_outbox
                set status = ?, attempts = ?, last_error = ?, updated_at = current_timestamp
                where id = ? and status = 'PENDING'
                """, status, attempts, message, row.id());
    }

    private record Row(
            String id,
            String workspaceId,
            String memoryItemId,
            String operation,
            String payloadJson,
            int attempts
    ) {
    }
}
