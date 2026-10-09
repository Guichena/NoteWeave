package com.noteweave.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.task.TaskService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finalizes source/snapshot/task together when the source-parse command cannot be delivered,
 * or when the consumer exhausts its retries and the record is sent to the dead-letter topic.
 */
@Service
public class SourceParseFailureFinalizer {
    private static final Logger log = LoggerFactory.getLogger(SourceParseFailureFinalizer.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskService taskService;
    private final SourceCatalogVersionService sourceCatalogVersionService;

    public SourceParseFailureFinalizer(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TaskService taskService,
            SourceCatalogVersionService sourceCatalogVersionService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.taskService = taskService;
        this.sourceCatalogVersionService = sourceCatalogVersionService;
    }

    @Transactional
    public void finalizeDeliveryExhausted(String taskId, String payloadJson) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        if (!eligible(task)) {
            return;
        }
        Map<String, Object> payload = readPayload(payloadJson);
        markFailed(
                task,
                text(payload.get("workspaceId")),
                text(payload.get("sourceId")),
                text(payload.get("snapshotId")),
                "SYSTEM:SOURCE_PARSE_OUTBOX",
                "OUTBOX_DEAD_LETTER",
                "Kafka Source Parse 投递耗尽，资料解析未执行",
                "OUTBOX_DISPATCH_EXHAUSTED",
                true
        );
    }

    /**
     * 消费者重试耗尽、消息进入死信队列时收尾。解析事务已经回滚，快照仍停在 PENDING，
     * 这里把快照、当前资料和任务一起标记为失败，并保留原始错误码供前端展示。
     * 同一条死信被重复处理时，任务已是终态，直接返回。
     */
    @Transactional
    public void finalizeProcessingFailed(
            String taskId,
            String workspaceId,
            String sourceId,
            String snapshotId,
            String errorCode,
            String errorMessage,
            boolean retryable
    ) {
        TaskService.TaskRef task = taskService.getTaskRef(taskId);
        if (!eligible(task)) {
            return;
        }
        markFailed(
                task,
                workspaceId,
                sourceId,
                snapshotId,
                "SYSTEM:SOURCE_PARSE_CONSUMER",
                "SOURCE_PARSE_FAILED",
                "资料解析失败：" + abbreviate(errorMessage),
                errorCode,
                retryable
        );
    }

    private void markFailed(
            TaskService.TaskRef task,
            String workspaceId,
            String sourceId,
            String snapshotId,
            String actor,
            String phase,
            String message,
            String errorCode,
            boolean retryable
    ) {
        String taskId = task.taskId();
        if (blank(workspaceId) || blank(sourceId) || blank(snapshotId)
                || !workspaceId.equals(task.workspaceId())
                || !sourceId.equals(task.targetId())) {
            throw new BusinessException(
                    "SOURCE_PARSE_OUTBOX_IDENTITY_MISMATCH",
                    "Source parse dead-letter identity does not match the owning task"
            );
        }
        task = taskService.lockTaskRef(taskId);
        if (!eligible(task)) {
            return;
        }
        if (!workspaceId.equals(task.workspaceId()) || !sourceId.equals(task.targetId())) {
            throw new BusinessException(
                    "SOURCE_PARSE_OUTBOX_IDENTITY_MISMATCH",
                    "Source parse dead-letter identity changed while acquiring the task lock"
            );
        }

        int snapshotFailed = jdbcTemplate.update("""
                update source_snapshot
                set parse_status = 'FAILED', index_status = 'FAILED'
                where id = ? and source_id = ? and parse_status = 'PENDING'
                """, snapshotId, sourceId);
        int sourceFailed = jdbcTemplate.update("""
                update source
                set status = 'FAILED', parse_status = 'FAILED', index_status = 'FAILED',
                    updated_by = ?, updated_at = current_timestamp
                where id = ? and workspace_id = ? and status <> 'DELETED'
                  and exists (
                      select 1 from source_snapshot snapshot
                      where snapshot.id = ? and snapshot.source_id = source.id
                        and snapshot.version_no = (
                            select max(current_snapshot.version_no)
                            from source_snapshot current_snapshot
                            where current_snapshot.source_id = source.id
                        )
                  )
                """, actor, sourceId, workspaceId, snapshotId);
        if (sourceFailed == 1) {
            sourceCatalogVersionService.bump(workspaceId);
        }
        if (snapshotFailed == 0 && sourceFailed == 0) {
            log.info("Skip stale source-parse dead letter: taskId={}, sourceId={}, snapshotId={}",
                    taskId, sourceId, snapshotId);
        }
        taskService.failTask(taskId, phase, message, errorCode, retryable);
    }

    // 任务的 error_message 为 varchar(1000)，由错误码和说明拼接而成，这里给说明留出余量。
    private String abbreviate(String message) {
        String value = blank(message) ? "资料解析过程中出错" : message.trim();
        return value.length() <= 400 ? value : value.substring(0, 400) + "…";
    }

    private Map<String, Object> readPayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException(
                    "SOURCE_PARSE_OUTBOX_PAYLOAD_INVALID",
                    "Source parse dead-letter payload is invalid"
            );
        }
    }

    private boolean eligible(TaskService.TaskRef task) {
        return "SOURCE_PARSE".equals(task.taskType())
                && "SOURCE".equals(task.targetType())
                && !terminal(task.taskStatus());
    }

    private boolean terminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
