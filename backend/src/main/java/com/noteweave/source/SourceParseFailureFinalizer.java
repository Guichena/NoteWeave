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

/** Finalizes source/snapshot/task together when the source-parse command cannot be delivered. */
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
        String workspaceId = text(payload.get("workspaceId"));
        String sourceId = text(payload.get("sourceId"));
        String snapshotId = text(payload.get("snapshotId"));
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
                    updated_by = 'SYSTEM:SOURCE_PARSE_OUTBOX', updated_at = current_timestamp
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
                """, sourceId, workspaceId, snapshotId);
        if (sourceFailed == 1) {
            sourceCatalogVersionService.bump(workspaceId);
        }
        if (snapshotFailed == 0 && sourceFailed == 0) {
            log.info("Skip stale source-parse dead letter: taskId={}, sourceId={}, snapshotId={}",
                    taskId, sourceId, snapshotId);
        }
        taskService.failTask(
                taskId,
                "OUTBOX_DEAD_LETTER",
                "Kafka Source Parse 投递耗尽，资料解析未执行",
                "OUTBOX_DISPATCH_EXHAUSTED",
                true
        );
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
