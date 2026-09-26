package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
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

    public VideoLearningRequestRepository(JdbcTemplate jdbc,
                                          ArtifactVideoMaterialService videoMaterials) {
        this.jdbc = jdbc;
        this.videoMaterials = videoMaterials;
    }

    @Transactional
    public ParentReceipt createOrReplay(String workspaceId, String clientRequestId,
                                        VideoLearningRequestDraft draft) {
        if (clientRequestId == null || clientRequestId.isBlank()
                || clientRequestId.length() > 120 || draft == null) {
            throw invalid("request identity is missing");
        }
        String id = Ids.newId();
        String digest = draft.digest();
        try {
            jdbc.update("""
                    insert into video_learning_request(
                        id, workspace_id, client_request_id, request_digest, video_url,
                        part_no, language, frame_density, asr_fallback, template_version,
                        user_requirement, material_state)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED')
                    """, id, workspaceId, clientRequestId, digest, draft.videoUrl(),
                    draft.part(), draft.language(), draft.frameDensity(), draft.asrFallback(),
                    draft.templateVersion(), draft.userRequirement());
        } catch (DuplicateKeyException conflict) {
            List<ParentReceipt> existing = jdbc.query("""
                    select id, request_digest, material_state from video_learning_request
                    where workspace_id = ? and client_request_id = ?
                    """, (rs, index) -> new ParentReceipt(rs.getString("id"),
                    rs.getString("request_digest"), rs.getString("material_state"), true),
                    workspaceId, clientRequestId);
            if (existing.size() != 1 || !digest.equals(existing.get(0).requestDigest())) {
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
        return new ParentReceipt(id, digest, "QUEUED", false);
    }

    @Transactional
    public void attachMaterialTask(String workspaceId, String requestId, String taskId) {
        int updated = jdbc.update("""
                update video_learning_request
                set material_task_id = ?, updated_at = current_timestamp
                where id = ? and workspace_id = ? and material_task_id is null
                  and material_state = 'QUEUED' and cancellation_requested = false
                """, taskId, requestId, workspaceId);
        if (updated != 1) throw invalid("material task was already attached or request cancelled");
    }

    @Transactional
    public void markMaterialReady(String workspaceId, String requestId,
                                  String bundleId, String planId) {
        List<ParentInput> parents = jdbc.query("""
                select video_url, part_no, frame_density, asr_fallback
                from video_learning_request where id = ? and workspace_id = ? for update
                """, (rs, index) -> new ParentInput(rs.getString(1), rs.getInt(2),
                rs.getString(3), rs.getString(4)), requestId, workspaceId);
        if (parents.size() != 1) throw invalid("parent request does not belong to Workspace");
        ParentInput parent = parents.get(0);
        ArtifactVideoMaterialService.ParentMaterialIdentity identity =
                videoMaterials.requireParentMaterial(workspaceId, bundleId, planId,
                parent.videoUrl(), parent.part(), parent.frameDensity(), parent.asrFallback());
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

    private static BusinessException invalid(String detail) {
        return new BusinessException("VIDEO_LEARNING_STATE_INVALID", detail, HttpStatus.CONFLICT);
    }

    public record ParentReceipt(String requestId, String requestDigest,
                                String materialState, boolean replayed) {}
    private record ParentInput(String videoUrl, int part, String frameDensity,
                               String asrFallback) {}
}
