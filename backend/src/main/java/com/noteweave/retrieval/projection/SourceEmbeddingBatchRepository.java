package com.noteweave.retrieval.projection;

import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.source.SourceTopicNames;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 批次状态和真实上下文调用账本。模型调用始终在事务外执行。 */
@Repository
public class SourceEmbeddingBatchRepository {
    public static final int BATCH_SIZE = 64;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SourceTopicNames topicNames;
    private final int leaseSeconds;

    public SourceEmbeddingBatchRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
            SourceTopicNames topicNames) {
        this(jdbc, manager, topicNames, 7200);
    }

    @Autowired
    public SourceEmbeddingBatchRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
            com.noteweave.config.NoteWeaveProperties properties,
            SourceTopicNames topicNames,
            @Value("${noteweave.kafka.max-poll-interval-ms:1800000}") int pollMillis,
            @Value("${noteweave.pipeline.embedding-lease-seconds:7200}") int configuredLeaseSeconds) {
        this(jdbc, manager, topicNames,
                com.noteweave.source.SourcePipelineProcessingBudget.embeddingLeaseSeconds(
                        properties, pollMillis, configuredLeaseSeconds));
    }

    public SourceEmbeddingBatchRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
            SourceTopicNames topicNames, int leaseSeconds) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.topicNames = java.util.Objects.requireNonNull(topicNames, "topicNames");
        if (leaseSeconds < 1 || leaseSeconds > 86400) throw new IllegalArgumentException("批次租约必须在 1 至 86400 秒之间");
        this.leaseSeconds = leaseSeconds;
    }

    /** 初始化消息只冻结批次并投递，重复消息不会重复投递同一任务的批次。 */
    public List<Batch> initialize(String workspace, String source, String snapshot, String task,
            String embedding, String chunker, String context,
            java.util.function.BiFunction<Integer, Boolean, Integer> embeddingBudget) {
        requireVersion(embedding); requireVersion(chunker); requireVersion(context);
        return transactions.execute(status -> {
            if (!lockCurrent(workspace, source, snapshot, task, "EMBEDDING")) return List.of();
            List<String> digests = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            jdbc.query("""
                    select id, content, coalesce(heading, '') as heading from source_chunk
                    where workspace_id = ? and source_id = ? and source_snapshot_id = ? order by chunk_no, id
                    """, rs -> { ids.add(rs.getString(1)); digests.add(chunkHash(rs.getString(2), rs.getString(3))); }, workspace, source, snapshot);
            if (ids.isEmpty()) throw new IllegalStateException("资料快照没有可向量化的片段");
            String metadataDigest = sourceMetadataDigest(workspace, source);
            String version = hash(embedding + "\n" + chunker + "\n" + context + "\n" + metadataDigest + "\n" + digest(ids, digests));
            jdbc.update("update source_snapshot set embedding_batch_version = ? where id = ?", version, snapshot);
            int totalBatches = (ids.size() + BATCH_SIZE - 1) / BATCH_SIZE;
            List<Batch> existing = list(workspace, source, snapshot, version);
            if (existing.isEmpty()) {
                for (int batch = 0; batch < totalBatches; batch++) {
                    int offset = batch * BATCH_SIZE;
                    int count = Math.min(BATCH_SIZE, ids.size() - offset);
                    jdbc.update("""
                            insert into source_embedding_batch(id, workspace_id, source_id, source_snapshot_id,
                                task_id, batch_version, embedding_version, chunker_version, context_prompt_version,
                                batch_no, chunk_offset, chunk_count, total_chunks, total_batches, chunk_digest,
                                source_metadata_digest, embedding_request_budget, embedding_requests_used, status)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'READY')
                            """, Ids.newId(), workspace, source, snapshot, task, version, embedding, chunker, context,
                            batch, offset, count, ids.size(), totalBatches,
                            digest(ids.subList(offset, offset + count), digests.subList(offset, offset + count)),
                            metadataDigest, embeddingBudget.apply(count, batch == totalBatches - 1));
                    enqueue(workspace, source, snapshot, task, version, batch);
                }
            } else {
                if (existing.size() != totalBatches) throw new IllegalStateException("批次检查点不完整");
                for (Batch batch : existing) {
                    if (!task.equals(batch.taskId())) {
                        // 新任务失效旧租约；已完成结果和调用账本保留。
                        jdbc.update("""
                                update source_embedding_batch set task_id = ?, lease_token = null, lease_until = null,
                                    status = case when status = 'COMPLETED' then status else 'READY' end,
                                    updated_at = current_timestamp where id = ?
                                """, task, batch.id());
                        if (!"COMPLETED".equals(batch.status())) enqueue(workspace, source, snapshot, task, version, batch.batchNo());
                    }
                }
            }
            return list(workspace, source, snapshot, version);
        });
    }

    public Batch claim(String workspace, String source, String snapshot, String task, int batchNo, String version) {
        return transactions.execute(status -> {
            if (!lockCurrent(workspace, source, snapshot, task, "EMBEDDING")) return null;
            if (!activeVersion(snapshot, version)) return null;
            Batch row = find(workspace, source, snapshot, version, batchNo);
            if (row == null || !task.equals(row.taskId()) || "COMPLETED".equals(row.status())) return null;
            if ("RUNNING".equals(row.status()) && row.leaseUntil() != null && row.leaseUntil().isAfter(databaseNow())) {
                throw new IllegalStateException("SOURCE_EMBEDDING_BATCH_BUSY");
            }
            if (!row.chunkDigest().equals(currentChunkDigest(row))) throw new IllegalStateException("批次片段已变化");
            if (!row.sourceMetadataDigest().equals(sourceMetadataDigest(workspace, source))) throw new IllegalStateException("资料元数据已变化");
            String token = Ids.newId();
            jdbc.update("""
                    update source_embedding_batch set status = 'RUNNING', lease_token = ?,
                        lease_until = timestampadd(second, ?, current_timestamp),
                        updated_at = current_timestamp where id = ?
                    """, token, leaseSeconds, row.id());
            return find(workspace, source, snapshot, version, batchNo);
        });
    }

    /** 调用前写入累计账本，失败请求同样计数；租约或任务失效后禁止继续调用。 */
    public void beforeModelCall(Batch claimed, int increment) {
        if (increment != 1) throw new IllegalArgumentException("每次只能记录一次真实模型调用");
        transactions.executeWithoutResult(status -> {
            requireOwned(claimed);
            int changed = jdbc.update("""
                    update source_embedding_batch set context_model_calls = context_model_calls + 1,
                        updated_at = current_timestamp where id = ? and context_model_calls < 2
                    """, claimed.id());
            if (changed != 1) throw new com.noteweave.common.BusinessException("SOURCE_CONTEXT_CALL_BUDGET_EXHAUSTED", "片段上下文模型调用预算已耗尽");
        });
    }

    /** 每次真实向量 HTTP 尝试前扣一次；失败和未知发送结果不退还额度。 */
    public void beforeEmbeddingRequest(Batch claimed) {
        transactions.executeWithoutResult(status -> {
            requireOwned(claimed);
            Integer used = jdbc.queryForObject("select embedding_requests_used from source_embedding_batch where id = ?",
                    Integer.class, claimed.id());
            if (used == null) throw new com.noteweave.retrieval.provider.RetrievalProviderException(
                    "SOURCE_EMBEDDING_BUDGET_LEGACY_UNKNOWN", "历史批次的向量请求消耗未知，禁止自动追加请求");
            int changed = jdbc.update("""
                    update source_embedding_batch set embedding_requests_used = embedding_requests_used + 1,
                        updated_at = current_timestamp
                    where id = ? and status = 'RUNNING' and lease_token = ? and lease_until > current_timestamp
                      and embedding_requests_used < embedding_request_budget
                    """, claimed.id(), claimed.leaseToken());
            if (changed != 1) {
                requireOwned(claimed);
                throw new com.noteweave.retrieval.provider.RetrievalProviderException(
                        "SOURCE_EMBEDDING_REQUEST_BUDGET_EXHAUSTED", "批次向量请求预算已耗尽");
            }
        });
    }

    /** 运行期续租只延长当前仍有效的 token，过期持有者不能恢复所有权。 */
    public void renew(Batch claimed) {
        transactions.executeWithoutResult(status -> {
            requireOwned(claimed);
            if (jdbc.update("""
                    update source_embedding_batch set lease_until = timestampadd(second, ?, current_timestamp),
                        updated_at = current_timestamp
                    where id = ? and status = 'RUNNING' and lease_token = ? and lease_until > current_timestamp
                    """, leaseSeconds, claimed.id(), claimed.leaseToken()) != 1)
                throw new IllegalStateException("SOURCE_EMBEDDING_BATCH_LEASE_LOST");
        });
    }

    long heartbeatIntervalMillis() {
        return Math.max(100L, Math.min(60000L, leaseSeconds * 1000L / 3));
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("select current_timestamp", Timestamp.class).toInstant();
    }

    /** 派生文件已经持久化后提交指针，最后完成者在同一资料行锁内独占索引投递。 */
    public boolean complete(Batch claimed, String objectKey, String sha256,
            java.util.function.Function<List<Batch>, String> saveManifest) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            requireOwned(claimed);
            if (!claimed.chunkDigest().equals(currentChunkDigest(claimed))) throw new IllegalStateException("批次片段已变化");
            int completed = jdbc.update("""
                    update source_embedding_batch set status = 'COMPLETED', embeddings_object_key = ?,
                        embeddings_sha256 = ?, lease_token = null, lease_until = null, updated_at = current_timestamp
                    where id = ? and status = 'RUNNING' and lease_token = ? and lease_until > current_timestamp
                    """, objectKey, sha256, claimed.id(), claimed.leaseToken());
            if (completed != 1) throw new IllegalStateException("SOURCE_EMBEDDING_BATCH_LEASE_LOST");
            return queueIndexIfComplete(claimed.workspaceId(), claimed.sourceId(), claimed.snapshotId(),
                    claimed.taskId(), claimed.batchVersion(), saveManifest);
        }));
    }

    public boolean queueIndexIfComplete(String workspace, String source, String snapshot, String task, String version,
            java.util.function.Function<List<Batch>, String> saveManifest) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!lockCurrent(workspace, source, snapshot, task, "EMBEDDING")) return false;
            if (!activeVersion(snapshot, version)) return false;
            List<Batch> batches = list(workspace, source, snapshot, version);
            if (batches.isEmpty() || batches.size() != batches.get(0).totalBatches()
                    || batches.stream().anyMatch(batch -> !"COMPLETED".equals(batch.status()))) return false;
            String manifest = saveManifest.apply(batches);
            int moved = jdbc.update("""
                    update source_snapshot set processing_stage = 'INDEXING'
                    where id = ? and source_id = ? and processing_stage = 'EMBEDDING'
                      and parse_status = 'PARSED' and index_status = 'INDEXING'
                    """, snapshot, source);
            if (moved != 1) return false;
            Map<String, Object> payload = identity(workspace, source, snapshot, task);
            payload.put("batchVersion", version);
            payload.put("embeddingsObjectKey", manifest);
            jdbc.update("""
                    insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                    values (?, ?, ?, ?, ?, 'READY')
                    """, Ids.newId(), task, topicNames.index(), snapshot, Json.write(MAPPER, payload));
            return true;
        }));
    }

    public void release(Batch claimed) {
        jdbc.update("""
                update source_embedding_batch set status = 'READY', lease_token = null, lease_until = null,
                    updated_at = current_timestamp where id = ? and status = 'RUNNING' and lease_token = ?
                      and lease_until > current_timestamp
                """, claimed.id(), claimed.leaseToken());
    }

    /** 当前资格先于 limit，旧任务不能挤占恢复名额；READY 与过期 RUNNING 共用恢复事务。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${noteweave.pipeline.embedding-recovery-delay-ms:60000}")
    public void recoverExpiredLeases() {
        List<Batch> due = jdbc.query("""
                select b.* from source_embedding_batch b
                join source s on s.id = b.source_id and s.workspace_id = b.workspace_id
                join source_snapshot ss on ss.id = b.source_snapshot_id and ss.source_id = s.id
                join task t on t.id = b.task_id and t.workspace_id = b.workspace_id
                where s.status = 'PROCESSING' and s.index_status = 'INDEXING'
                  and ss.parse_status = 'PARSED' and ss.index_status = 'INDEXING' and ss.processing_stage = 'EMBEDDING'
                  and ss.embedding_batch_version = b.batch_version
                  and (ss.embedding_task_id is null or ss.embedding_task_id = b.task_id)
                  and ss.version_no = (select max(n.version_no) from source_snapshot n where n.source_id = s.id)
                  and t.task_type = 'SOURCE_PARSE' and t.target_type = 'SOURCE' and t.target_id = s.id
                  and t.task_status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                  and not exists (select 1 from task n where n.workspace_id = b.workspace_id
                    and n.task_type = 'SOURCE_PARSE' and n.target_type = 'SOURCE' and n.target_id = s.id
                    and n.id <> t.id and n.created_at > t.created_at)
                  and ((b.status = 'RUNNING' and b.lease_until <= current_timestamp)
                    or (b.status = 'READY' and (b.next_recovery_at <= current_timestamp
                      or (b.next_recovery_at is null and b.updated_at <= timestampadd(second, -300, current_timestamp)))))
                order by b.updated_at, b.id limit 100
                """, this::map);
        for (Batch row : due) {
            transactions.executeWithoutResult(status -> {
                if (!lockCurrent(row.workspaceId(), row.sourceId(), row.snapshotId(), row.taskId(), "EMBEDDING")
                        || !activeVersion(row.snapshotId(), row.batchVersion())) return;
                int changed = jdbc.update("""
                        update source_embedding_batch set status = 'READY', lease_token = null, lease_until = null,
                            next_recovery_at = timestampadd(second, 300, current_timestamp), updated_at = current_timestamp
                        where id = ? and task_id = ? and batch_version = ?
                          and ((status = 'RUNNING' and lease_token = ? and lease_until <= current_timestamp)
                            or (status = 'READY' and (next_recovery_at <= current_timestamp
                              or (next_recovery_at is null and updated_at <= timestampadd(second, -300, current_timestamp)))))
                        """, row.id(), row.taskId(), row.batchVersion(), row.leaseToken());
                if (changed == 1) enqueue(row.workspaceId(), row.sourceId(), row.snapshotId(), row.taskId(), row.batchVersion(), row.batchNo());
            });
        }
    }

    /** 索引及死信收尾都必须核对当前任务，完成批次的晚到失败不会破坏快照。 */
    public boolean acceptsFailure(String workspace, String source, String snapshot, String task, int batchNo, String version) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!lockCurrent(workspace, source, snapshot, task, "EMBEDDING")) return false;
            if (!activeVersion(snapshot, version)) return false;
            Batch row = find(workspace, source, snapshot, version, batchNo);
            return row != null && task.equals(row.taskId()) && !"COMPLETED".equals(row.status())
                    && !("RUNNING".equals(row.status()) && row.leaseUntil() != null && row.leaseUntil().isAfter(databaseNow()));
        }));
    }

    public boolean current(String workspace, String source, String snapshot, String task, String stage) {
        return Boolean.TRUE.equals(transactions.execute(status -> lockCurrent(workspace, source, snapshot, task, stage)));
    }

    public List<Batch> list(String workspace, String source, String snapshot, String version) {
        return jdbc.query("""
                select * from source_embedding_batch where workspace_id = ? and source_id = ?
                    and source_snapshot_id = ? and batch_version = ? order by batch_no
                """, this::map, workspace, source, snapshot, version);
    }

    private Batch find(String workspace, String source, String snapshot, String version, int batch) {
        List<Batch> rows = jdbc.query("""
                select * from source_embedding_batch where workspace_id = ? and source_id = ?
                    and source_snapshot_id = ? and batch_version = ? and batch_no = ?
                """, this::map, workspace, source, snapshot, version, batch);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void requireOwned(Batch batch) {
        if (!lockCurrent(batch.workspaceId(), batch.sourceId(), batch.snapshotId(), batch.taskId(), "EMBEDDING"))
            throw new IllegalStateException("SOURCE_EMBEDDING_BATCH_STALE");
        if (!activeVersion(batch.snapshotId(), batch.batchVersion())) throw new IllegalStateException("SOURCE_EMBEDDING_BATCH_STALE");
        if (!batch.sourceMetadataDigest().equals(sourceMetadataDigest(batch.workspaceId(), batch.sourceId())))
            throw new IllegalStateException("SOURCE_EMBEDDING_BATCH_STALE: 资料元数据已变化");
        Batch row = find(batch.workspaceId(), batch.sourceId(), batch.snapshotId(), batch.batchVersion(), batch.batchNo());
        if (row == null || !"RUNNING".equals(row.status()) || !batch.taskId().equals(row.taskId())
                || !batch.leaseToken().equals(row.leaseToken()) || row.leaseUntil() == null || !row.leaseUntil().isAfter(databaseNow()))
            throw new IllegalStateException("SOURCE_EMBEDDING_BATCH_LEASE_LOST");
    }

    public boolean activeVersion(String snapshot, String version) {
        return version != null && version.equals(jdbc.queryForObject(
                "select embedding_batch_version from source_snapshot where id = ?", String.class, snapshot));
    }

    private boolean lockCurrent(String workspace, String source, String snapshot, String task, String stage) {
        if (task == null || task.isBlank()) return false;
        List<Boolean> matches = jdbc.query("""
                select s.status, s.index_status, ss.processing_stage, ss.parse_status, ss.index_status as snapshot_index,
                    ss.version_no, ss.embedding_task_id, (select max(n.version_no) from source_snapshot n where n.source_id = s.id) as latest
                from source s join source_snapshot ss on ss.source_id = s.id and ss.id = ?
                where s.workspace_id = ? and s.id = ? for update
                """, (rs, row) -> "PROCESSING".equals(rs.getString("status")) && "INDEXING".equals(rs.getString("index_status"))
                    && stage.equals(rs.getString("processing_stage")) && "PARSED".equals(rs.getString("parse_status"))
                    && "INDEXING".equals(rs.getString("snapshot_index")) && rs.getInt("version_no") == rs.getInt("latest")
                    && (rs.getString("embedding_task_id") == null || task.equals(rs.getString("embedding_task_id"))),
                snapshot, workspace, source);
        if (matches.isEmpty() || !matches.get(0)) return false;
        List<Boolean> tasks = jdbc.query("""
                select workspace_id, task_type, target_type, target_id, task_status from task where id = ? for update
                """, (rs, row) -> workspace.equals(rs.getString("workspace_id")) && "SOURCE_PARSE".equals(rs.getString("task_type"))
                    && "SOURCE".equals(rs.getString("target_type")) && source.equals(rs.getString("target_id"))
                    && !List.of("COMPLETED", "FAILED", "CANCELLED").contains(rs.getString("task_status")), task);
        if (tasks.isEmpty() || !tasks.get(0)) return false;
        Integer newer = jdbc.queryForObject("""
                select count(*) from task newer join task current_task on current_task.id = ?
                where newer.workspace_id = ? and newer.task_type = 'SOURCE_PARSE' and newer.target_type = 'SOURCE'
                  and newer.target_id = ? and newer.id <> current_task.id
                  and newer.created_at > current_task.created_at
                """, Integer.class, task, workspace, source);
        if (newer != null && newer > 0) return false;
        jdbc.update("update source_snapshot set embedding_task_id = ? where id = ? and embedding_task_id is null", task, snapshot);
        return true;
    }

    private String currentChunkDigest(Batch batch) {
        List<String> ids = new ArrayList<>(); List<String> hashes = new ArrayList<>();
        jdbc.query("""
                select id, content, coalesce(heading, '') as heading from source_chunk where workspace_id = ? and source_id = ?
                    and source_snapshot_id = ? order by chunk_no, id limit ? offset ?
                """, rs -> { ids.add(rs.getString(1)); hashes.add(chunkHash(rs.getString(2), rs.getString(3))); },
                batch.workspaceId(), batch.sourceId(), batch.snapshotId(), batch.chunkCount(), batch.chunkOffset());
        return digest(ids, hashes);
    }

    private String sourceMetadataDigest(String workspace, String source) {
        return jdbc.queryForObject("""
                select title, source_type, coalesce(summary, ''), coalesce(tags_json, '[]'), coalesce(metadata_json, '{}')
                from source where workspace_id = ? and id = ?
                """, (rs, row) -> hash(Json.write(MAPPER, List.of(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)))), workspace, source);
    }

    private static String chunkHash(String content, String heading) {
        return hash(Json.write(MAPPER, List.of(content, heading)));
    }

    private void enqueue(String workspace, String source, String snapshot, String task, String version, int batch) {
        jdbc.update("""
                update source_embedding_batch set next_recovery_at = timestampadd(second, 300, current_timestamp)
                where source_snapshot_id = ? and batch_version = ? and batch_no = ? and task_id = ?
                """, snapshot, version, batch, task);
        // Kafka 分区键保持稳定；防重另核对版本，不能让旧版本在途消息挡住新计划。
        List<String> inFlight = jdbc.queryForList("""
                select payload_json from task_outbox where task_id = ? and topic = ? and message_key = ?
                  and status in ('READY', 'PROCESSING')
                """, String.class, task, topicNames.embed(), snapshot + ":" + batch);
        for (String json : inFlight) {
            try {
                var existing = MAPPER.readTree(json);
                if (existing != null && workspace.equals(existing.path("workspaceId").asText())
                        && source.equals(existing.path("sourceId").asText())
                        && snapshot.equals(existing.path("sourceSnapshotId").asText())
                        && task.equals(existing.path("taskId").asText())
                        && version.equals(existing.path("batchVersion").asText())
                        && existing.path("batchNo").isIntegralNumber()
                        && existing.path("batchNo").asInt(-1) == batch) return;
            } catch (java.io.IOException invalidPayload) {
                // 无效载荷不能证明已有同一批次消息，交由既有死信处理。
            }
        }
        Map<String, Object> payload = identity(workspace, source, snapshot, task);
        payload.put("batchNo", batch); payload.put("batchVersion", version);
        jdbc.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, ?, ?, ?, 'READY')
                """, Ids.newId(), task, topicNames.embed(), snapshot + ":" + batch, Json.write(MAPPER, payload));
    }

    private Map<String, Object> identity(String workspace, String source, String snapshot, String task) {
        Map<String, Object> map = new LinkedHashMap<>(); map.put("workspaceId", workspace); map.put("sourceId", source);
        map.put("sourceSnapshotId", snapshot); map.put("taskId", task); return map;
    }

    private Batch map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp lease = rs.getTimestamp("lease_until");
        return new Batch(rs.getString("id"), rs.getString("workspace_id"), rs.getString("source_id"),
                rs.getString("source_snapshot_id"), rs.getString("task_id"), rs.getString("batch_version"),
                rs.getString("embedding_version"), rs.getString("chunker_version"), rs.getString("context_prompt_version"),
                rs.getInt("batch_no"), rs.getInt("chunk_offset"), rs.getInt("chunk_count"), rs.getInt("total_chunks"),
                rs.getInt("total_batches"), rs.getString("chunk_digest"), rs.getString("source_metadata_digest"),
                rs.getString("status"), rs.getString("lease_token"),
                lease == null ? null : lease.toInstant(), rs.getInt("context_model_calls"), rs.getInt("embedding_request_budget"),
                rs.getString("embeddings_object_key"), rs.getString("embeddings_sha256"));
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String digest(List<String> ids, List<String> hashes) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) value.append(ids.get(i)).append(':').append(hashes.get(i)).append('\n');
        return hash(value.toString());
    }
    private static void requireVersion(String value) {
        if (value == null || value.isBlank() || value.length() > 300) throw new IllegalArgumentException("无效的批次版本");
    }

    public record Batch(String id, String workspaceId, String sourceId, String snapshotId, String taskId,
            String batchVersion, String embeddingVersion, String chunkerVersion, String contextPromptVersion,
            int batchNo, int chunkOffset, int chunkCount, int totalChunks, int totalBatches, String chunkDigest, String sourceMetadataDigest,
            String status, String leaseToken, Instant leaseUntil, int modelCalls, int embeddingRequestBudget,
            String objectKey, String sha256) { }
}
