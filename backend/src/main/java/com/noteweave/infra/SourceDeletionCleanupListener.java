package com.noteweave.infra;

import com.noteweave.common.Ids;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.infra.outbox.OutboxDispatchPolicy;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import com.noteweave.source.SourceDeletedEvent;
import com.noteweave.storage.ObjectStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SourceDeletionCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(SourceDeletionCleanupListener.class);
    private static final int MAX_ATTEMPTS = 5;
    /** 死信计数器，Prometheus 导出为 noteweave_source_cleanup_dead_letter_total。 */
    private static final String DEAD_LETTER_METRIC = "noteweave.source.cleanup.dead_letter";
    /** DATA-02：归属不明、无法安全回收的对象键计数，按清理类型打标签。 */
    private static final String UNTRACKED_OBJECT_METRIC = "noteweave.source.cleanup.untracked_object";
    private static final String EXPIRED_LEASE_ERROR = "cleanup lease expired after maximum attempts";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectStorage objectStorage;
    private final ObjectProvider<RetrievalProjectionWriter> projectionWriter;
    private final String sourceBucket;
    private final boolean elasticsearchEnabled;
    private final MeterRegistry meterRegistry;

    /** 文件对象生命周期网关；未注入时保留既有引用检查（legacy 检查-删除竞态）。 */
    private com.noteweave.source.storage.FileObjectLifecycleGateway fileObjectLifecycle;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFileObjectLifecycle(com.noteweave.source.storage.FileObjectLifecycleGateway fileObjectLifecycle) {
        this.fileObjectLifecycle = fileObjectLifecycle;
    }

    /** 兼容没有指标注册表的调用方，此时只保留结构化日志。 */
    public SourceDeletionCleanupListener(
            JdbcTemplate jdbcTemplate,
            ObjectStorage objectStorage,
            ObjectProvider<RetrievalProjectionWriter> projectionWriter,
            NoteWeaveProperties properties
    ) {
        this(jdbcTemplate, objectStorage, projectionWriter, properties, null);
    }

    @Autowired
    public SourceDeletionCleanupListener(
            JdbcTemplate jdbcTemplate,
            ObjectStorage objectStorage,
            ObjectProvider<RetrievalProjectionWriter> projectionWriter,
            NoteWeaveProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectStorage = objectStorage;
        this.projectionWriter = projectionWriter;
        this.sourceBucket = properties.storage().minio().bucketSource();
        this.elasticsearchEnabled = properties.elasticsearch().enabled();
        this.meterRegistry = meterRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void prepareCleanup(SourceDeletedEvent event) {
        // 投影清理任务在首次删除时已按业务身份登记；升级只补登记缺失的对象键清理意图
        if (event.isUpgrade() && event.objectKeys().isEmpty()) {
            return;
        }
        if (!event.isUpgrade() && elasticsearchEnabled) {
            for (ProjectionType type : ProjectionType.values()) {
                jdbcTemplate.update("""
                        insert into source_cleanup_task(
                            id, workspace_id, source_id, cleanup_type, projection_type, status, next_attempt_at
                        )
                        select ?, ?, ?, 'RETRIEVAL_PROJECTION', ?, 'READY', current_timestamp
                        where not exists (
                            select 1 from source_cleanup_task
                            where source_id = ? and cleanup_type = 'RETRIEVAL_PROJECTION'
                              and projection_type = ?
                        )
                        """, Ids.newId(), event.workspaceId(), event.sourceId(), type.name(),
                        event.sourceId(), type.name());
            }
        }
        for (String objectKey : event.objectKeys()) {
            jdbcTemplate.update("""
                    insert into source_cleanup_task(
                        id, workspace_id, source_id, cleanup_type, bucket_name, object_key,
                        object_key_sha256, status, next_attempt_at
                    )
                    select ?, ?, ?, 'SOURCE_OBJECT', ?, ?, ?, 'READY', current_timestamp
                    where not exists (
                        select 1 from source_cleanup_task
                        where source_id = ? and cleanup_type = 'SOURCE_OBJECT'
                          and bucket_name = ? and object_key = ?
                    )
                    """, Ids.newId(), event.workspaceId(), event.sourceId(), sourceBucket, objectKey,
                    sha256(objectKey), event.sourceId(), sourceBucket, objectKey);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void cleanup(SourceDeletedEvent event) {
        dispatchReadyTasks(event.sourceId(), 10);
    }

    @Scheduled(fixedDelayString = "${noteweave.source.cleanup-redrive-delay-ms:5000}")
    public void retryCleanupTasks() {
        dispatchReadyTasks(null, 50);
    }

    private void dispatchReadyTasks(String sourceId, int limit) {
        deadLetterExpiredExhaustedTasks(sourceId, limit);
        String sql = """
                select id, workspace_id, source_id, cleanup_type, projection_type,
                       bucket_name, object_key, attempt_count
                from source_cleanup_task
                where attempt_count < ? and (
                    (status = 'READY' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                    or (status = 'PROCESSING' and lease_until < current_timestamp)
                )
                """ + (sourceId == null ? "" : " and source_id = ? ") + """
                order by created_at, id
                limit ?
                """;
        Object[] arguments = sourceId == null
                ? new Object[]{MAX_ATTEMPTS, limit}
                : new Object[]{MAX_ATTEMPTS, sourceId, limit};
        List<CleanupTask> candidates = jdbcTemplate.query(sql, (rs, rowNum) -> new CleanupTask(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("source_id"),
                rs.getString("cleanup_type"),
                rs.getString("projection_type"),
                rs.getString("bucket_name"),
                rs.getString("object_key"),
                rs.getInt("attempt_count")
        ), arguments);
        for (CleanupTask candidate : candidates) {
            dispatch(candidate);
        }
    }

    private void dispatch(CleanupTask candidate) {
        String leaseOwner = Ids.newId();
        int claimed = jdbcTemplate.update("""
                update source_cleanup_task
                set status = 'PROCESSING', attempt_count = attempt_count + 1,
                    lease_owner = ?,
                    lease_until = timestampadd(second, ?, current_timestamp),
                    last_error = null
                where id = ? and attempt_count = ? and attempt_count < ? and (
                    (status = 'READY' and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                    or (status = 'PROCESSING' and lease_until < current_timestamp)
                )
                """, leaseOwner, OutboxDispatchPolicy.LEASE_DURATION.toSeconds(),
                candidate.id(), candidate.attemptCount(), MAX_ATTEMPTS);
        if (claimed != 1) {
            return;
        }
        int attempt = candidate.attemptCount() + 1;
        try {
            execute(candidate);
            jdbcTemplate.update("""
                    update source_cleanup_task
                    set status = 'COMPLETED', lease_owner = null, lease_until = null,
                        completed_at = current_timestamp
                    where id = ? and status = 'PROCESSING' and lease_owner = ?
                    """, candidate.id(), leaseOwner);
        } catch (RuntimeException ex) {
            fail(candidate, leaseOwner, attempt, ex);
        }
    }

    /**
     * 重试次数已经用尽、租约又过期的任务无法再被领取，只能落到死信。
     * 这里补上 workspace、投影类型和最后错误，死信记录才能被检索和追查。
     */
    private void deadLetterExpiredExhaustedTasks(String sourceId, int limit) {
        String sql = """
                select id, workspace_id, source_id, cleanup_type, projection_type,
                       attempt_count, last_error
                from source_cleanup_task
                where status = 'PROCESSING' and attempt_count >= ?
                  and (lease_until is null or lease_until < current_timestamp)
                """ + (sourceId == null ? "" : " and source_id = ? ") + """
                order by created_at, id
                limit ?
                """;
        Object[] arguments = sourceId == null
                ? new Object[]{MAX_ATTEMPTS, limit}
                : new Object[]{MAX_ATTEMPTS, sourceId, limit};
        List<ExhaustedTask> exhausted = jdbcTemplate.query(sql, (rs, rowNum) -> new ExhaustedTask(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("source_id"),
                rs.getString("cleanup_type"),
                rs.getString("projection_type"),
                rs.getInt("attempt_count"),
                rs.getString("last_error")
        ), arguments);
        for (ExhaustedTask task : exhausted) {
            int updated = jdbcTemplate.update("""
                    update source_cleanup_task
                    set status = 'DEAD_LETTER', lease_owner = null, lease_until = null,
                        dead_lettered_at = current_timestamp,
                        last_error = coalesce(last_error, ?)
                    where id = ? and status = 'PROCESSING' and attempt_count = ?
                      and attempt_count >= ?
                      and (lease_until is null or lease_until < current_timestamp)
                    """, EXPIRED_LEASE_ERROR, task.id(), task.attemptCount(), MAX_ATTEMPTS);
            if (updated == 1) {
                String lastError = task.lastError() == null ? EXPIRED_LEASE_ERROR : task.lastError();
                reportDeadLetter(task.id(), task.workspaceId(), task.sourceId(), task.cleanupType(),
                        task.projectionType(), task.attemptCount(), lastError);
            }
        }
    }

    private void execute(CleanupTask task) {
        if ("RETRIEVAL_PROJECTION".equals(task.cleanupType())) {
            RetrievalProjectionWriter writer = projectionWriter.getIfAvailable();
            if (writer == null) {
                throw new IllegalStateException("Retrieval projection writer is unavailable");
            }
            ProjectionType type = ProjectionType.valueOf(task.projectionType());
            writer.deleteSource(RetrievalIndexNames.alias(type, task.workspaceId()), task.sourceId());
            return;
        }
        if ("SOURCE_OBJECT".equals(task.cleanupType())) {
            // 零引用删除先取得绑定对象键与代次的删除票据；共享原件仍被引用时跳过。
            // 网关未接线时退回既有按 object_key 的引用检查（检查-删除竞态，保持旧行为）。
            // object_key 全局含 workspace 前缀，按键定位文件对象行；端口认领再带 workspace 判定
            java.util.List<String> fileObjectIds = jdbcTemplate.queryForList(
                    "select id from file_object where object_key = ? limit 1",
                    String.class, task.objectKey());
            String fileObjectId = fileObjectIds.isEmpty() ? null : fileObjectIds.get(0);
            if (fileObjectLifecycle != null && fileObjectId == null) {
                // NR14：快照独占原件（上传/生成/研究报告为 source_snapshot 复制的独立副本）
                // 没有 file_object 行——以 DB 快照登记为归属证明：该来源的快照行登记此 key
                // 且无其他来源共享时直接物理回收；此前被当未知对象跳过后任务标 COMPLETED，
                // 普通与 COMPLIANCE 删除都永久遗留原件
                Integer ownedSnapshots = jdbcTemplate.queryForObject("""
                        select count(*) from source_snapshot ss
                        where ss.object_key = ? and ss.source_id = ?
                        """, Integer.class, task.objectKey(), task.sourceId());
                Integer sharedSnapshots = jdbcTemplate.queryForObject("""
                        select count(*) from source_snapshot ss
                        where ss.object_key = ? and ss.source_id <> ?
                        """, Integer.class, task.objectKey(), task.sourceId());
                if (ownedSnapshots != null && ownedSnapshots > 0
                        && (sharedSnapshots == null || sharedSnapshots == 0)) {
                    objectStorage.delete(task.bucketName(), task.objectKey());
                    return;
                }
                // DATA-02：归属不明不能当「清理完成」。此前这里直接 return，
                // 任务照样标 COMPLETED，对象却永久留在库里，COMPLIANCE 删除等于没删。
                // 抛出让任务按重试计数走死信，停在 DEAD_LETTER 等人工核对，并单独计数。
                if (meterRegistry != null) {
                    meterRegistry.counter(UNTRACKED_OBJECT_METRIC, "cleanup_type", task.cleanupType())
                            .increment();
                }
                log.error("Source cleanup found untracked object key, dead-lettering for manual review: "
                                + "sourceId={}, objectKey={}", task.sourceId(), task.objectKey());
                throw new IllegalStateException(
                        "untracked object key, needs manual review: " + task.objectKey());
            }
            java.util.Optional<com.noteweave.source.storage.FileObjectLifecyclePort.DeletionTicket> ticket =
                    fileObjectLifecycle != null
                            ? fileObjectLifecycle.claimZeroReferenceDeletion(
                                    task.workspaceId(), fileObjectId, task.bucketName(), task.objectKey())
                            : legacyClaimByObjectKey(task.objectKey());
            if (ticket.isEmpty()) {
                // AU-005：区分「票据已认领（对账器负责物理删除）」与「仍被引用（共享对象不得删）」，
                // 前者多发生在本任务早前一次认领后物理删除失败的重试
                if (fileObjectLifecycle != null) {
                    java.util.List<String> statuses = jdbcTemplate.queryForList(
                            "select status from file_object where id = ?", String.class, fileObjectId);
                    String rowStatus = statuses.isEmpty() ? null : statuses.get(0);
                    if ("DELETE_CLAIMED".equals(rowStatus) || "DELETED".equals(rowStatus)) {
                        log.info("Source cleanup: deletion ticket already claimed, physical delete owned by ticket reconciler: sourceId={}, objectKey={}",
                                task.sourceId(), task.objectKey());
                        return;
                    }
                }
                log.info("Source cleanup skipped object still referenced: sourceId={}, objectKey={}",
                        task.sourceId(), task.objectKey());
                return;
            }
            objectStorage.delete(task.bucketName(), ticket.get().objectKey());
            return;
        }
        throw new IllegalStateException("Unknown source cleanup type: " + task.cleanupType());
    }

    /** legacy 路径：沿用改造前的按 object_key 引用检查。 */
    private java.util.Optional<com.noteweave.source.storage.FileObjectLifecyclePort.DeletionTicket> legacyClaimByObjectKey(String objectKey) {
        Integer activeReferences = jdbcTemplate.queryForObject(
                "select count(*) from file_object where object_key = ? and ref_count > 0",
                Integer.class, objectKey);
        if (activeReferences != null && activeReferences > 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new com.noteweave.source.storage.FileObjectLifecyclePort.DeletionTicket(
                null, null, objectKey, 0L));
    }

    private void fail(CleanupTask task, String leaseOwner, int attempt, RuntimeException exception) {
        String error = OutboxDispatchPolicy.abbreviate(exception.getMessage(), exception.getClass().getSimpleName());
        if (attempt >= MAX_ATTEMPTS) {
            int updated = jdbcTemplate.update("""
                    update source_cleanup_task
                    set status = 'DEAD_LETTER', last_error = ?, lease_owner = null,
                        lease_until = null, dead_lettered_at = current_timestamp
                    where id = ? and status = 'PROCESSING' and lease_owner = ?
                    """, error, task.id(), leaseOwner);
            if (updated == 1) {
                reportDeadLetter(task.id(), task.workspaceId(), task.sourceId(), task.cleanupType(),
                        task.projectionType(), attempt, error);
                return;
            }
            log.warn("Source cleanup dead letter was skipped because the lease was lost: "
                            + "taskId={}, sourceId={}, type={}",
                    task.id(), task.sourceId(), task.cleanupType());
            return;
        }
        jdbcTemplate.update("""
                update source_cleanup_task
                set status = 'READY', last_error = ?, lease_owner = null, lease_until = null,
                    next_attempt_at = timestampadd(second, ?, current_timestamp)
                where id = ? and status = 'PROCESSING' and lease_owner = ?
                """, error, OutboxDispatchPolicy.retryDelaySeconds(attempt), task.id(), leaseOwner);
        log.warn("Source cleanup failed and will retry: sourceId={}, type={}, attempt={}, reason={}",
                task.sourceId(), task.cleanupType(), attempt, error);
    }

    /**
     * 死信是清理链路的终点，删除失败到这里就没人再重试了。
     * 统一输出可检索的 ERROR 日志并累加计数器，避免清理失败无人发现。
     */
    private void reportDeadLetter(
            String taskId,
            String workspaceId,
            String sourceId,
            String cleanupType,
            String projectionType,
            int attemptCount,
            String lastError
    ) {
        log.error("Source cleanup dead letter: taskId={}, workspaceId={}, sourceId={}, "
                        + "cleanupType={}, projectionType={}, attemptCount={}, lastError={}",
                taskId, workspaceId, sourceId, cleanupType,
                projectionType == null ? "-" : projectionType, attemptCount,
                OutboxDispatchPolicy.abbreviate(lastError, EXPIRED_LEASE_ERROR));
        if (meterRegistry != null) {
            meterRegistry.counter(DEAD_LETTER_METRIC, "cleanup_type", cleanupType).increment();
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ExhaustedTask(
            String id,
            String workspaceId,
            String sourceId,
            String cleanupType,
            String projectionType,
            int attemptCount,
            String lastError
    ) { }

    private record CleanupTask(
            String id,
            String workspaceId,
            String sourceId,
            String cleanupType,
            String projectionType,
            String bucketName,
            String objectKey,
            int attemptCount
    ) { }
}
