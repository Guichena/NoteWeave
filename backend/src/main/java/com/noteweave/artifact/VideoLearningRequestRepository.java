package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.task.TaskService;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable parent selection and child-link CAS. Dispatch is wired separately. */
@Repository
public class VideoLearningRequestRepository {
    private final JdbcTemplate jdbc;
    private final ArtifactVideoMaterialService videoMaterials;
    private final TaskService tasks;

    public VideoLearningRequestRepository(JdbcTemplate jdbc,
                                          ArtifactVideoMaterialService videoMaterials,
                                          TaskService tasks) {
        this.jdbc = jdbc;
        this.videoMaterials = videoMaterials;
        this.tasks = tasks;
    }

    @Transactional
    public ParentReceipt createOrReplay(String workspaceId, String actorId, String clientRequestId,
                                        VideoLearningRequestDraft draft) {
        if (actorId == null || actorId.isBlank()
                || clientRequestId == null || clientRequestId.isBlank()
                || clientRequestId.length() > 120 || draft == null) {
            throw invalid("request identity is missing");
        }
        String id = Ids.newId();
        String digest = draft.digest();
        try {
            jdbc.update("""
                    insert into video_learning_request(
                        id, workspace_id, actor_user_id, client_request_id, request_digest, video_url,
                        part_no, language, frame_density, asr_fallback, template_version,
                        user_requirement, material_state)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED')
                    """, id, workspaceId, actorId, clientRequestId, digest, draft.videoUrl(),
                    draft.part(), draft.language(), draft.frameDensity(), draft.asrFallback(),
                    draft.templateVersion(), draft.userRequirement());
        } catch (DuplicateKeyException conflict) {
            List<ParentReceipt> existing = jdbc.query("""
                    select id, actor_user_id, request_digest, material_state from video_learning_request
                    where workspace_id = ? and client_request_id = ?
                    """, (rs, index) -> new ParentReceipt(rs.getString("id"),
                    rs.getString("actor_user_id"), rs.getString("request_digest"),
                    rs.getString("material_state"), true),
                    workspaceId, clientRequestId);
            if (existing.size() != 1 || !actorId.equals(existing.get(0).actorId())
                    || !digest.equals(existing.get(0).requestDigest())) {
                throw new BusinessException("VIDEO_LEARNING_REQUEST_CONFLICT",
                        "同一请求 ID 已用于不同的视频学习选择", HttpStatus.CONFLICT);
            }
            return existing.get(0);
        }
        for (String skill : draft.selectedSkills()) {
            jdbc.update("""
                    insert into video_learning_request_choice(request_id, skill_key)
                    values (?, ?)
                    """, id, skill);
        }
        return new ParentReceipt(id, actorId, digest, "QUEUED", false);
    }

    @Transactional
    public void attachMaterialTask(String workspaceId, String requestId, String taskId) {
        int updated = jdbc.update("""
                update video_learning_request
                set material_task_id = ?, updated_at = current_timestamp
                where id = ? and workspace_id = ? and material_task_id is null
                  and material_state = 'QUEUED' and cancellation_requested = false
                  and exists (
                    select 1 from task t where t.id = ? and t.workspace_id = ?
                      and t.task_type = 'VIDEO_MATERIAL'
                      and t.target_type = 'VIDEO_LEARNING_REQUEST'
                      and t.target_id = video_learning_request.id
                      and t.task_status = 'PENDING'
                  )
                """, taskId, requestId, workspaceId, taskId, workspaceId);
        if (updated != 1) throw invalid("material task was already attached or request cancelled");
    }

    public VideoMaterialTaskInput workerInput(String taskId) {
        List<VideoMaterialTaskInput> inputs = jdbc.query("""
                select r.id, r.workspace_id, r.video_url, r.part_no, r.language,
                       r.frame_density, r.asr_fallback, r.template_version
                from video_learning_request r
                join task t on t.id = r.material_task_id
                join workspace w on w.id = r.workspace_id and w.status = 'ACTIVE'
                join users u on u.id = r.actor_user_id and u.status = 'ACTIVE'
                join workspace_member m on m.workspace_id = r.workspace_id
                    and m.user_id = r.actor_user_id and m.status = 'ACTIVE'
                    and m.role in ('OWNER', 'EDITOR')
                where t.id = ? and t.workspace_id = r.workspace_id
                  and t.task_type = 'VIDEO_MATERIAL'
                  and t.target_type = 'VIDEO_LEARNING_REQUEST' and t.target_id = r.id
                  and t.task_status in ('PENDING', 'RUNNING', 'WAITING')
                  and r.material_state in ('QUEUED', 'RUNNING')
                  and r.cancellation_requested = false
                """, (rs, index) -> {
            String requestId = rs.getString(1);
            return new VideoMaterialTaskInput("video-material-input.v1", taskId,
                    requestId, rs.getString(2), requestId, rs.getString(8), Map.of(
                    "url", rs.getString(3), "part", String.valueOf(rs.getInt(4)),
                    "language", rs.getString(5), "frame_density", rs.getString(6),
                    "asr_fallback", rs.getString(7)));
        }, taskId);
        if (inputs.size() != 1) throw invalid("material task input is unavailable");
        return inputs.get(0);
    }

    @Transactional
    public void markMaterialReady(String workspaceId, String requestId,
                                  String bundleId, String planId) {
        List<ParentInput> parents = jdbc.query("""
                select video_url, part_no, frame_density, asr_fallback, material_task_id
                from video_learning_request r
                join workspace w on w.id = r.workspace_id and w.status = 'ACTIVE'
                join users u on u.id = r.actor_user_id and u.status = 'ACTIVE'
                join workspace_member m on m.workspace_id = r.workspace_id
                    and m.user_id = r.actor_user_id and m.status = 'ACTIVE'
                    and m.role in ('OWNER', 'EDITOR')
                where r.id = ? and r.workspace_id = ? for update
                """, (rs, index) -> new ParentInput(rs.getString(1), rs.getInt(2),
                rs.getString(3), rs.getString(4), rs.getString(5)), requestId, workspaceId);
        if (parents.size() != 1) throw invalid("parent request does not belong to Workspace");
        ParentInput parent = parents.get(0);
        ArtifactVideoMaterialService.ParentMaterialIdentity identity =
                videoMaterials.requireParentMaterial(workspaceId, bundleId, planId,
                requestId, parent.materialTaskId(), parent.videoUrl(), parent.part(),
                parent.frameDensity(), parent.asrFallback());
        int updated = jdbc.update("""
                update video_learning_request
                set material_bundle_id = ?, knowledge_plan_id = ?,
                    material_content_digest = ?, knowledge_plan_content_digest = ?,
                    material_state = 'READY',
                    updated_at = current_timestamp
                where id = ? and workspace_id = ? and material_state in ('QUEUED', 'RUNNING')
                  and cancellation_requested = false
                  and material_bundle_id is null and knowledge_plan_id is null
                """, bundleId, planId, identity.bundleDigest(), identity.planDigest(),
                requestId, workspaceId);
        if (updated != 1) throw invalid("material request cannot accept a new Bundle");
    }

    public List<String> missingChoices(String workspaceId, String requestId) {
        return jdbc.query("""
                select c.skill_key from video_learning_request_choice c
                join video_learning_request r on r.id = c.request_id
                where r.id = ? and r.workspace_id = ? and r.material_state = 'READY'
                  and r.cancellation_requested = false and c.artifact_job_id is null
                order by c.skill_key
                """, (rs, index) -> rs.getString(1), requestId, workspaceId);
    }

    /** Background coordination must recheck the original actor's current ACL. */
    public String requireActorMayOperate(String workspaceId, String requestId) {
        List<String> actors = jdbc.query("""
                select r.actor_user_id from video_learning_request r
                join workspace w on w.id = r.workspace_id
                join users u on u.id = r.actor_user_id
                join workspace_member m on m.workspace_id = r.workspace_id
                    and m.user_id = r.actor_user_id
                where r.id = ? and r.workspace_id = ? and r.material_state = 'READY'
                  and r.cancellation_requested = false
                  and w.status = 'ACTIVE' and u.status = 'ACTIVE'
                  and m.status = 'ACTIVE' and m.role in ('OWNER', 'EDITOR')
                """, (rs, index) -> rs.getString(1), requestId, workspaceId);
        if (actors.size() != 1) {
            throw new BusinessException("VIDEO_LEARNING_ACTOR_REVOKED",
                    "请求发起者已失去工作台产物执行权限", HttpStatus.FORBIDDEN);
        }
        return actors.get(0);
    }

    @Transactional
    public void attachChild(String workspaceId, String requestId, String skillKey,
                            String artifactJobId) {
        int updated = jdbc.update("""
                update video_learning_request_choice
                set artifact_job_id = ?
                where request_id = ? and skill_key = ? and artifact_job_id is null
                  and exists (
                    select 1 from video_learning_request r
                    where r.id = request_id and r.workspace_id = ?
                      and r.material_state = 'READY' and r.cancellation_requested = false
                  )
                  and exists (
                    select 1 from artifact_job j
                    where j.id = ? and j.workspace_id = ? and j.skill_key = ?
                  )
                """, artifactJobId, requestId, skillKey, workspaceId,
                artifactJobId, workspaceId, skillKey);
        if (updated != 1) throw invalid("child Job is missing, mismatched or already linked");
    }

    /** The parent status is a projection; child terminal states remain independent. */
    public List<ParentView> recentForActor(String workspaceId, String actorId) {
        List<String> ids = jdbc.query("""
                select id from video_learning_request
                where workspace_id = ? and actor_user_id = ?
                order by created_at desc, id desc limit 20
                """, (rs, index) -> rs.getString(1), workspaceId, actorId);
        return ids.stream().map(id -> view(workspaceId, id)).toList();
    }

    /** The parent status is a projection; child terminal states remain independent. */
    public ParentView view(String workspaceId, String requestId) {
        List<ParentView> parents = jdbc.query("""
                select r.id, r.material_state, r.material_task_id, r.material_bundle_id,
                       r.knowledge_plan_id, r.cancellation_requested, t.task_status,
                       r.video_url, r.part_no
                from video_learning_request r
                left join task t on t.id = r.material_task_id
                where r.id = ? and r.workspace_id = ?
                """, (rs, index) -> new ParentView(rs.getString(1),
                projectedMaterialState(rs.getString(2), rs.getString(7)),
                rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getBoolean(6), rs.getString(8), rs.getInt(9), List.of()),
                requestId, workspaceId);
        if (parents.size() != 1) throw invalid("parent request does not belong to Workspace");
        ParentView parent = parents.get(0);
        List<ChoiceView> choices = jdbc.query("""
                select c.skill_key, c.artifact_job_id, j.status, j.task_id, t.task_status
                from video_learning_request_choice c
                left join artifact_job j on j.id = c.artifact_job_id
                left join task t on t.id = j.task_id
                where c.request_id = ? order by c.skill_key
                """, (rs, index) -> new ChoiceView(rs.getString(1), rs.getString(2),
                projectedChildState(rs.getString(3), rs.getString(5)), rs.getString(4)),
                requestId);
        return new ParentView(parent.requestId(), parent.materialState(), parent.materialTaskId(),
                parent.materialBundleId(), parent.knowledgePlanId(),
                parent.cancellationRequested(), parent.videoUrl(), parent.part(), choices);
    }

    /** Stop undelivered work and record an intent for already dispatched work. */
    @Transactional
    public ParentView requestCancellation(String workspaceId, String requestId, String actorId) {
        List<String> materialTasks = jdbc.query("""
                select material_task_id from video_learning_request
                where id = ? and workspace_id = ? and actor_user_id = ? for update
                """, (rs, index) -> rs.getString(1), requestId, workspaceId, actorId);
        if (materialTasks.size() != 1) throw invalid("parent request does not belong to actor");
        jdbc.update("""
                update video_learning_request
                set cancellation_requested = true, updated_at = current_timestamp
                where id = ? and workspace_id = ? and cancellation_requested = false
                """, requestId, workspaceId);
        List<String> taskIds = new ArrayList<>();
        if (materialTasks.get(0) != null) taskIds.add(materialTasks.get(0));
        taskIds.addAll(jdbc.query("""
                select j.task_id from video_learning_request_choice c
                join artifact_job j on j.id = c.artifact_job_id
                where c.request_id = ? and j.workspace_id = ?
                """, (rs, index) -> rs.getString(1), requestId, workspaceId));
        for (String taskId : taskIds) {
            // Only a READY outbox proves the Worker has not taken this task.
            jdbc.update("""
                    update task_outbox set status = 'CANCELLED'
                    where task_id = ? and status in ('READY', 'DEAD_LETTER')
                    """, taskId);
            Integer inFlight = jdbc.queryForObject("""
                    select count(*) from task_outbox
                    where task_id = ? and status in ('PROCESSING', 'SENT')
                    """, Integer.class, taskId);
            if (inFlight != null && inFlight == 0) {
                List<String> pending = jdbc.query("""
                        select id from task where id = ? and task_status = 'PENDING' for update
                        """, (rs, index) -> rs.getString(1), taskId);
                if (!pending.isEmpty()) {
                    tasks.cancelTask(taskId, "CANCELLED", "父请求已取消", "");
                    jdbc.update("""
                            update artifact_job set status = 'CANCELLED', updated_at = current_timestamp
                            where task_id = ? and status = 'QUEUED' and latest_version_no = 0
                            """, taskId);
                }
            }
        }
        return view(workspaceId, requestId);
    }

    private static String projectedMaterialState(String state, String taskState) {
        if ("READY".equals(state) || "FAILED".equals(state)
                || "CANCELLED".equals(state) || "DEGRADED".equals(state)) return state;
        if ("FAILED".equals(taskState)) return "FAILED";
        if ("CANCELLED".equals(taskState)) return "CANCELLED";
        if ("RUNNING".equals(taskState) || "WAITING".equals(taskState)) return "RUNNING";
        return state;
    }

    private static String projectedChildState(String jobState, String taskState) {
        if (jobState == null) return "NOT_STARTED";
        if ("CANCELLED".equals(taskState)) return "CANCELLED";
        if ("FAILED".equals(taskState)) return "FAILED";
        return jobState;
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException("VIDEO_LEARNING_STATE_INVALID", detail, HttpStatus.CONFLICT);
    }

    public record ParentReceipt(String requestId, String actorId, String requestDigest,
                                String materialState, boolean replayed) {}
    public record ParentView(String requestId, String materialState, String materialTaskId,
                             String materialBundleId, String knowledgePlanId,
                             boolean cancellationRequested, String videoUrl, int part,
                             List<ChoiceView> choices) {}
    public record ChoiceView(String skillKey, String artifactJobId, String status,
                             String taskId) {}
    private record ParentInput(String videoUrl, int part, String frameDensity,
                               String asrFallback, String materialTaskId) {}
}
