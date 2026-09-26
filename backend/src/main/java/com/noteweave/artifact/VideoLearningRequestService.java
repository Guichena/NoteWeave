package com.noteweave.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.noteweave.security.CurrentUserProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import com.noteweave.security.WorkspacePermission;
import com.noteweave.task.TaskService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VideoLearningRequestService {
    private final VideoLearningRequestRepository requests;
    private final TaskService tasks;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorkspaceAccessGuard access;
    private final CurrentUserProvider users;
    private final VideoLearningRolloutPolicy rollout;

    public VideoLearningRequestService(VideoLearningRequestRepository requests,
                                       TaskService tasks, JdbcTemplate jdbc, ObjectMapper mapper,
                                       WorkspaceAccessGuard access, CurrentUserProvider users,
                                       VideoLearningRolloutPolicy rollout) {
        this.requests = requests;
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.access = access;
        this.users = users;
        this.rollout = rollout;
    }

    public boolean isEnabled(String workspaceId) {
        return rollout.allows(workspaceId);
    }

    @Transactional
    public VideoLearningRequestRepository.ParentView create(String workspaceId,
                                                             CreateVideoLearningBundleRequest request) {
        if (!rollout.allows(workspaceId)) {
            throw new BusinessException("VIDEO_LEARNING_NOT_ENABLED",
                    "视频学习聚合入口尚未开放", HttpStatus.SERVICE_UNAVAILABLE);
        }
        access.requirePermission(workspaceId, WorkspacePermission.EXECUTION_OPERATE);
        if (request == null) {
            throw new BusinessException("VIDEO_LEARNING_REQUEST_INVALID",
                    "视频学习请求不能为空", HttpStatus.BAD_REQUEST);
        }
        VideoLearningRequestDraft draft = request.draft();
        String actorId = users.requireUserId();
        var parent = requests.createOrReplay(workspaceId, actorId,
                request.clientRequestId(), draft);
        if (!parent.replayed()) {
            String taskId = tasks.createTask(workspaceId, "VIDEO_MATERIAL",
                    "VIDEO_LEARNING_REQUEST", parent.requestId(), "QUEUED", "视频资料采集已创建");
            requests.attachMaterialTask(workspaceId, parent.requestId(), taskId);
            jdbc.update("""
                    insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                    values (?, ?, 'noteweave.artifact.job', ?, ?, 'READY')
                    """, Ids.newId(), taskId, parent.requestId(), Json.write(mapper, Map.of(
                    "task_id", taskId,
                    "task_type", "VIDEO_MATERIAL",
                    "workspace_id", workspaceId,
                    "target_type", "VIDEO_LEARNING_REQUEST",
                    "target_id", parent.requestId(),
                    "payload_version", "v1"
            )));
        }
        return requests.view(workspaceId, parent.requestId());
    }
}
