package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reconciles each selected output against its own durable Artifact Job. */
@Service
public class VideoLearningChildCoordinator {
    private final JdbcTemplate jdbc;
    private final VideoLearningRequestRepository requests;
    private final ArtifactVideoMaterialService materials;
    private final ArtifactJobService jobs;

    public VideoLearningChildCoordinator(JdbcTemplate jdbc,
            VideoLearningRequestRepository requests, ArtifactVideoMaterialService materials,
            ArtifactJobService jobs) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.materials = materials;
        this.jobs = jobs;
    }

    public List<ChoiceKey> missingChoices(int limit) {
        return jdbc.query("""
                select r.workspace_id, r.id, c.skill_key
                from video_learning_request r
                join video_learning_request_choice c on c.request_id = r.id
                where r.material_state = 'READY' and r.cancellation_requested = false
                  and c.artifact_job_id is null
                order by r.created_at, r.id, c.skill_key
                limit ?
                """, (rs, index) -> new ChoiceKey(rs.getString(1), rs.getString(2),
                rs.getString(3)), Math.max(1, Math.min(limit, 100)));
    }

    @Transactional
    public boolean reconcileChoice(ChoiceKey key) {
        List<Parent> parents = jdbc.query("""
                select r.workspace_id, r.id, r.video_url, r.part_no, r.language,
                       r.frame_density, r.asr_fallback, r.user_requirement,
                       r.material_bundle_id, r.knowledge_plan_id, r.material_task_id,
                       r.material_content_digest, r.knowledge_plan_content_digest
                from video_learning_request r
                join video_learning_request_choice c on c.request_id = r.id
                where r.workspace_id = ? and r.id = ? and c.skill_key = ?
                  and c.artifact_job_id is null and r.material_state = 'READY'
                  and r.cancellation_requested = false
                for update
                """, (rs, index) -> new Parent(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13)),
                key.workspaceId(), key.requestId(), key.skillKey());
        if (parents.isEmpty()) return false;
        Parent parent = parents.get(0);
        String actorId = requests.requireActorMayOperate(key.workspaceId(), key.requestId());
        var frozen = materials.requireParentMaterial(parent.workspaceId(), parent.bundleId(), parent.planId(),
                parent.requestId(), parent.materialTaskId(), parent.videoUrl(), parent.part(),
                parent.frameDensity(), parent.asrFallback());
        if (!frozen.bundleDigest().equals(parent.bundleDigest())
                || !frozen.planDigest().equals(parent.planDigest())) {
            throw new BusinessException("VIDEO_MATERIAL_DEGRADED",
                    "父请求冻结的素材摘要已改变", HttpStatus.CONFLICT);
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("url", parent.videoUrl());
        inputs.put("part", String.valueOf(parent.part()));
        inputs.put("language", parent.language());
        inputs.put("frame_density", parent.frameDensity());
        inputs.put("asr_fallback", parent.asrFallback());
        inputs.put("video_material_bundle_id", parent.bundleId());
        ArtifactJobResponse job = jobs.createJobForVerifiedActor(parent.workspaceId(),
                new CreateArtifactJobRequest(key.skillKey(), parent.userRequirement(),
                        inputs, List.of(), List.of()), actorId);
        requests.attachChild(parent.workspaceId(), parent.requestId(), key.skillKey(),
                job.artifactJobId());
        return true;
    }

    @Transactional
    public VideoLearningRequestRepository.ParentView retryFailedChoice(
            String workspaceId, String requestId, String skillKey, String actorUserId) {
        List<RetryParent> parents = jdbc.query("""
                select r.video_url, r.part_no, r.frame_density, r.asr_fallback,
                       r.material_bundle_id, r.knowledge_plan_id, r.material_task_id,
                       r.material_content_digest, r.knowledge_plan_content_digest,
                       c.artifact_job_id
                from video_learning_request r
                join video_learning_request_choice c on c.request_id = r.id
                where r.id = ? and r.workspace_id = ? and c.skill_key = ?
                  and r.material_state = 'READY' and r.cancellation_requested = false
                  and c.artifact_job_id is not null
                for update
                """, (rs, index) -> new RetryParent(rs.getString(1), rs.getInt(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)),
                requestId, workspaceId, skillKey);
        if (parents.size() != 1) {
            throw new BusinessException("VIDEO_LEARNING_CHOICE_RETRY_CONFLICT",
                    "所选子产物当前不可重试", HttpStatus.CONFLICT);
        }
        String originalActor = requests.requireActorMayOperate(workspaceId, requestId);
        if (!originalActor.equals(actorUserId)) {
            throw new BusinessException("VIDEO_LEARNING_CHOICE_RETRY_FORBIDDEN",
                    "只有原请求发起者可以重试子产物", HttpStatus.FORBIDDEN);
        }
        RetryParent parent = parents.get(0);
        var frozen = materials.requireParentMaterial(workspaceId, parent.bundleId(), parent.planId(),
                requestId, parent.materialTaskId(), parent.videoUrl(), parent.part(),
                parent.frameDensity(), parent.asrFallback());
        if (!frozen.bundleDigest().equals(parent.bundleDigest())
                || !frozen.planDigest().equals(parent.planDigest())) {
            throw new BusinessException("VIDEO_MATERIAL_DEGRADED",
                    "父请求冻结的素材摘要已改变", HttpStatus.CONFLICT);
        }
        jobs.retryFailedJob(workspaceId, parent.artifactJobId());
        return requests.view(workspaceId, requestId);
    }

    public record ChoiceKey(String workspaceId, String requestId, String skillKey) {}
    private record Parent(String workspaceId, String requestId, String videoUrl,
                          int part, String language, String frameDensity, String asrFallback,
                          String userRequirement, String bundleId, String planId,
                          String materialTaskId, String bundleDigest, String planDigest) {}
    private record RetryParent(String videoUrl, int part, String frameDensity,
                               String asrFallback, String bundleId, String planId,
                               String materialTaskId, String bundleDigest,
                               String planDigest, String artifactJobId) {}
}
