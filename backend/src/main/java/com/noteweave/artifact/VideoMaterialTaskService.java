package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.infra.outbox.OutboxDispatchPolicy;
import com.noteweave.task.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Material-only Worker transaction boundary; publication follows Bundle and Plan validation. */
@Service
public class VideoMaterialTaskService {
    private static final String TOPIC = "noteweave.artifact.job";
    private final JdbcTemplate jdbc;
    private final DurableOutboxDispatcher outbox;
    private final TaskService tasks;
    private final VideoLearningRequestRepository requests;
    private final ArtifactVideoMaterialService materials;

    public VideoMaterialTaskService(JdbcTemplate jdbc, DurableOutboxDispatcher outbox,
                                    TaskService tasks, VideoLearningRequestRepository requests,
                                    ArtifactVideoMaterialService materials) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.tasks = tasks;
        this.requests = requests;
        this.materials = materials;
    }

    @Transactional
    public VideoMaterialTaskInput claimInput(String taskId, String deliveryToken) {
        lockParent(taskId);
        requireDelivery(taskId, deliveryToken);
        var task = requireMaterialTask(taskId);
        if ("PENDING".equals(task.taskStatus())) tasks.startTask(taskId);
        return requests.workerInput(taskId);
    }

    @Transactional
    public ArtifactVideoMaterialService.Receipt submitBundle(String taskId, String deliveryToken,
                                                              ArtifactVideoMaterialService.Submission submission) {
        lockParent(taskId);
        requireDelivery(taskId, deliveryToken);
        requireMaterialTask(taskId);
        return materials.submitParentMaterial(taskId, submission);
    }

    @Transactional
    public ArtifactVideoMaterialService.KnowledgeReceipt submitPlan(
            String taskId, String deliveryToken,
            ArtifactVideoMaterialService.KnowledgeSubmission submission) {
        lockParent(taskId);
        requireDelivery(taskId, deliveryToken);
        requireMaterialTask(taskId);
        return materials.submitParentKnowledgePlan(taskId, submission);
    }

    @Transactional
    public String complete(String taskId, String deliveryToken, String bundleId, String planId) {
        String requestId = lockParent(taskId);
        requireDelivery(taskId, deliveryToken);
        var task = requireMaterialTask(taskId);
        if (!"RUNNING".equals(task.taskStatus()) && !"WAITING".equals(task.taskStatus())) {
            throw invalid("material task is not running");
        }
        Boolean cancelled = jdbc.queryForObject("""
                select cancellation_requested from video_learning_request where id = ?
                """, Boolean.class, requestId);
        if (Boolean.TRUE.equals(cancelled)) {
            tasks.cancelTask(taskId, "CANCELLED", "父请求已取消", "");
            jdbc.update("""
                    update video_learning_request set material_state = 'CANCELLED',
                        updated_at = current_timestamp
                    where id = ? and material_state in ('QUEUED', 'RUNNING')
                    """, requestId);
            acknowledge(taskId, deliveryToken);
            return "CANCELLED";
        }
        if (bundleId == null || bundleId.isBlank() || planId == null || planId.isBlank()) {
            throw invalid("material completion requires frozen Bundle and Plan IDs");
        }
        tasks.completeTask(taskId, "READY", "视频资料已冻结", bundleId);
        requests.markMaterialReady(task.workspaceId(), requestId, bundleId, planId);
        acknowledge(taskId, deliveryToken);
        return "READY";
    }

    @Transactional
    public String fail(String taskId, String deliveryToken, String errorCode) {
        String requestId = lockParent(taskId);
        requireDelivery(taskId, deliveryToken);
        var task = requireMaterialTask(taskId);
        if (!"PENDING".equals(task.taskStatus()) && !"RUNNING".equals(task.taskStatus())
                && !"WAITING".equals(task.taskStatus())) throw invalid("material task is terminal");
        Boolean cancelled = jdbc.queryForObject("""
                select cancellation_requested from video_learning_request where id = ?
                """, Boolean.class, requestId);
        if (Boolean.TRUE.equals(cancelled)) {
            tasks.cancelTask(taskId, "CANCELLED", "父请求已取消", "");
            jdbc.update("""
                    update video_learning_request set material_state = 'CANCELLED',
                        updated_at = current_timestamp
                    where id = ? and material_state in ('QUEUED', 'RUNNING')
                    """, requestId);
            acknowledge(taskId, deliveryToken);
            return "CANCELLED";
        }
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,79}")) {
            throw invalid("material failure code is invalid");
        }
        tasks.failTask(taskId, "FAILED", "视频资料采集失败", errorCode, false);
        jdbc.update("""
                update video_learning_request set material_state = 'FAILED',
                    updated_at = current_timestamp
                where id = ? and material_state in ('QUEUED', 'RUNNING')
                """, requestId);
        acknowledge(taskId, deliveryToken);
        return "FAILED";
    }

    private String lockParent(String taskId) {
        var ids = jdbc.query("""
                select id from video_learning_request where material_task_id = ? for update
                """, (rs, index) -> rs.getString(1), taskId);
        if (ids.size() != 1) throw invalid("parent material task is unavailable");
        return ids.get(0);
    }

    private TaskService.TaskRef requireMaterialTask(String taskId) {
        var task = tasks.lockTaskRef(taskId);
        if (!"VIDEO_MATERIAL".equals(task.taskType())
                || !"VIDEO_LEARNING_REQUEST".equals(task.targetType())) {
            throw invalid("task does not belong to video material runtime");
        }
        return task;
    }

    private void requireDelivery(String taskId, String token) {
        if (token == null || token.isBlank() || !outbox.renewTaskMessage(
                TOPIC, taskId, token, OutboxDispatchPolicy.ARTIFACT_LEASE_DURATION)) {
            throw invalid("video material delivery token is stale");
        }
    }

    private void acknowledge(String taskId, String token) {
        if (!outbox.acknowledgeTaskMessage(TOPIC, taskId, token)) {
            throw invalid("video material delivery could not be acknowledged");
        }
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException("VIDEO_MATERIAL_TASK_INVALID", detail, HttpStatus.CONFLICT);
    }
}
