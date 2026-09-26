package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.task.TaskService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoLearningRequestRepositoryContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskService tasks;
    @Autowired VideoLearningRequestRepository requests;
    @Autowired ArtifactVideoMaterialService videoMaterials;
    @Autowired VideoMaterialTaskService materialTasks;

    @Test
    void freezesOneToFourChoicesAndReplaysOnlyIdenticalRequest() throws Exception {
        String workspaceId = workspace();
        VideoLearningRequestDraft draft = draft(List.of("interview_qa", "knowledge_blog"));
        var first = requests.createOrReplay(workspaceId, "local-user", "client-1", draft);
        var replay = requests.createOrReplay(workspaceId, "local-user", "client-1",
                draft(List.of("knowledge_blog", "interview_qa")));
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(jdbc.queryForList("""
                select skill_key from video_learning_request_choice
                where request_id = ? order by skill_key
                """, String.class, first.requestId()))
                .containsExactly("interview_qa", "knowledge_blog");
        assertThat(requests.missingChoices(workspaceId, first.requestId())).isEmpty();
        assertThatThrownBy(() -> requests.markMaterialReady(workspaceId, first.requestId(),
                "missing-bundle", "missing-plan"))
                .hasMessageContaining("Workspace");
        assertThatThrownBy(() -> requests.createOrReplay(workspaceId, "local-user", "client-1",
                draft(List.of("video_learning_deck"))))
                .hasMessageContaining("不同");
        assertThat(jdbc.queryForObject("""
                select count(*) from video_learning_request where workspace_id = ?
                """, Integer.class, workspaceId)).isEqualTo(1);
    }

    @Test
    void materialTaskCanBeAttachedOnceAndCannotLeakAcrossWorkspaces() throws Exception {
        String workspaceId = workspace();
        String otherWorkspace = workspace();
        var receipt = requests.createOrReplay(workspaceId, "local-user", "client-task",
                draft(List.of("knowledge_blog")));
        String taskId = tasks.createTask(workspaceId, "VIDEO_MATERIAL",
                "VIDEO_LEARNING_REQUEST", receipt.requestId(), "QUEUED", "素材采集已创建");
        String wrongTarget = tasks.createTask(workspaceId, "VIDEO_MATERIAL",
                "VIDEO_LEARNING_REQUEST", java.util.UUID.randomUUID().toString(),
                "QUEUED", "另一父请求的素材任务");
        assertThatThrownBy(() -> requests.attachMaterialTask(workspaceId,
                receipt.requestId(), wrongTarget)).hasMessageContaining("material task");
        String wrongType = tasks.createTask(workspaceId, "ARTIFACT_JOB",
                "VIDEO_LEARNING_REQUEST", receipt.requestId(), "QUEUED", "错误任务类型");
        assertThatThrownBy(() -> requests.attachMaterialTask(workspaceId,
                receipt.requestId(), wrongType)).hasMessageContaining("material task");
        assertThatThrownBy(() -> requests.attachMaterialTask(otherWorkspace,
                receipt.requestId(), taskId)).hasMessageContaining("material task");
        requests.attachMaterialTask(workspaceId, receipt.requestId(), taskId);
        assertThatThrownBy(() -> requests.attachMaterialTask(workspaceId,
                receipt.requestId(), taskId)).hasMessageContaining("material task");
        assertThat(jdbc.queryForObject("""
                select material_task_id from video_learning_request where id = ?
                """, String.class, receipt.requestId())).isEqualTo(taskId);
    }

    @Test
    void rejectsUnpublishedChoicesAndConflictingPart() {
        assertThatThrownBy(() -> draft(List.of("knowledge_blog", "knowledge_blog")))
                .hasMessageContaining("视频学习请求");
        assertThatThrownBy(() -> draft(List.of("course_notes")))
                .hasMessageContaining("视频学习请求");
        assertThatThrownBy(() -> new VideoLearningRequestDraft(
                "https://www.bilibili.com/video/BV1234567890?p=3", 2, "zh-CN",
                "STANDARD", "ALLOW", "original-v1", "study", List.of("knowledge_blog")))
                .hasMessageContaining("视频学习请求");
        assertThatThrownBy(() -> new VideoLearningRequestDraft(
                "https://www.bilibili.com/video/BV1234567890", 1, null,
                "STANDARD", "ALLOW", "original-v1", "study", List.of("knowledge_blog")))
                .hasMessageContaining("视频学习请求");
    }

    @Test
    void publicCreationRemainsDisabledWithoutRolloutFlag() throws Exception {
        String workspaceId = workspace();
        mvc.perform(post("/api/v2/workspaces/{workspaceId}/video-learning-bundles", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(new CreateVideoLearningBundleRequest(
                        "disabled-client", "https://www.bilibili.com/video/BV1234567890",
                        1, "zh-CN", "STANDARD", "ALLOW", "original-v1", "study",
                        List.of("knowledge_blog")))))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("""
                select count(*) from video_learning_request where workspace_id = ?
                """, Integer.class, workspaceId)).isZero();
    }

    @Test
    void projectsChoicesAndCancelsOnlyUndeliveredMaterialWork() throws Exception {
        String workspaceId = workspace();
        var parent = requests.createOrReplay(workspaceId, "local-user", "client-cancel",
                draft(List.of("knowledge_blog", "video_learning_deck")));
        String taskId = tasks.createTask(workspaceId, "VIDEO_MATERIAL",
                "VIDEO_LEARNING_REQUEST", parent.requestId(), "QUEUED", "素材采集已创建");
        requests.attachMaterialTask(workspaceId, parent.requestId(), taskId);
        jdbc.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, 'noteweave.video.material', ?, '{}', 'READY')
                """, java.util.UUID.randomUUID().toString(), taskId, taskId);

        String response = mvc.perform(get("/api/v2/workspaces/{workspaceId}/video-learning-bundles/{requestId}",
                        workspaceId, parent.requestId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var data = mapper.readTree(response).path("data");
        assertThat(data.path("material_state").asText()).isEqualTo("QUEUED");
        assertThat(data.path("choices")).hasSize(2);
        assertThat(data.path("choices").get(0).path("status").asText())
                .isEqualTo("NOT_STARTED");

        String cancelResponse = mvc.perform(post(
                        "/api/v2/workspaces/{workspaceId}/video-learning-bundles/{requestId}/cancel",
                        workspaceId, parent.requestId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var cancelled = mapper.readTree(cancelResponse).path("data");
        assertThat(cancelled.path("cancellation_requested").asBoolean()).isTrue();
        assertThat(cancelled.path("material_state").asText()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from task_outbox where task_id = ?",
                String.class, taskId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select task_status from task where id = ?",
                String.class, taskId)).isEqualTo("CANCELLED");
        assertThat(requests.requestCancellation(workspaceId, parent.requestId(), "local-user")
                .cancellationRequested()).isTrue();
        assertThatThrownBy(() -> requests.requestCancellation(workspaceId,
                parent.requestId(), "another-user")).hasMessageContaining("actor");
    }

    @Test
    void cancellationRetainsAlreadyDispatchedTaskAsIntent() throws Exception {
        String workspaceId = workspace();
        var parent = requests.createOrReplay(workspaceId, "local-user", "client-running",
                draft(List.of("knowledge_blog")));
        String taskId = tasks.createTask(workspaceId, "VIDEO_MATERIAL",
                "VIDEO_LEARNING_REQUEST", parent.requestId(), "QUEUED", "素材采集已创建");
        requests.attachMaterialTask(workspaceId, parent.requestId(), taskId);
        tasks.startTask(taskId);
        jdbc.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json,
                                        status, lease_owner, lease_until)
                values (?, ?, 'noteweave.artifact.job', ?, '{}', 'PROCESSING', ?, ?)
                """, java.util.UUID.randomUUID().toString(), taskId, taskId,
                "cancel-delivery", java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(300)));

        var cancelled = requests.requestCancellation(workspaceId, parent.requestId(), "local-user");
        assertThat(cancelled.cancellationRequested()).isTrue();
        assertThat(cancelled.materialState()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select task_status from task where id = ?",
                String.class, taskId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select status from task_outbox where task_id = ?",
                String.class, taskId)).isEqualTo("PROCESSING");
        assertThat(materialTasks.complete(taskId, "cancel-delivery", "", ""))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select task_status from task where id = ?",
                String.class, taskId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select status from task_outbox where task_id = ?",
                String.class, taskId)).isEqualTo("SENT");
    }

    @Test
    void materialOnlyTaskFreezesBundleWithoutPdfJob() throws Exception {
        String workspaceId = workspace();
        var parent = requests.createOrReplay(workspaceId, "local-user", "client-material",
                draft(List.of("knowledge_blog")));
        String taskId = tasks.createTask(workspaceId, "VIDEO_MATERIAL",
                "VIDEO_LEARNING_REQUEST", parent.requestId(), "QUEUED", "素材采集已创建");
        requests.attachMaterialTask(workspaceId, parent.requestId(), taskId);
        tasks.startTask(taskId);
        Map<String, Object> inputs = Map.of("url",
                "https://www.bilibili.com/video/BV1234567890?p=2", "part", "2",
                "language", "zh-CN", "frame_density", "STANDARD", "asr_fallback", "ALLOW");
        String inputDigest = sha256(parent.requestId() + ":" + canonical(inputs));
        Map<String, Object> bundle = new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("schema_version", "video-material-v1"),
                Map.entry("bundle_id", "bundle-" + taskId), Map.entry("bundle_version", 1),
                Map.entry("workspace_id", workspaceId), Map.entry("bvid", "BV1234567890"),
                Map.entry("part", 2), Map.entry("duration_ms", 5000),
                Map.entry("input_digest", inputDigest), Map.entry("subtitle_source", "NONE"),
                Map.entry("transcript_original", ""), Map.entry("transcript_corrected", ""),
                Map.entry("transcript_segments", List.of()), Map.entry("frames", List.of()),
                Map.entry("files", List.of()), Map.entry("coverage_gaps", List.of("NO_SUBTITLE", "NO_FRAMES")),
                Map.entry("knowledge_nodes", List.of(Map.of("node_id", "n1", "title", "缺少资料",
                        "start_ms", 0, "end_ms", 5000, "transcript_segment_ids", List.of(),
                        "frame_ids", List.of(), "missing", List.of("NO_SUBTITLE", "NO_FRAMES"))))));
        var submission = new ArtifactVideoMaterialService.Submission(bundle, sha256(canonical(bundle)));
        var receipt = videoMaterials.submitParentMaterial(taskId, submission);
        assertThat(videoMaterials.submitParentMaterial(taskId, submission).id()).isEqualTo(receipt.id());
        assertThat(jdbc.queryForMap("""
                select artifact_job_id, video_learning_request_id from artifact_video_material_bundle
                where id = ?
                """, receipt.id())).containsEntry("video_learning_request_id", parent.requestId())
                .containsEntry("artifact_job_id", null);
        Map<String, Object> modified = new java.util.LinkedHashMap<>(bundle);
        modified.put("duration_ms", 6000);
        assertThatThrownBy(() -> videoMaterials.submitParentMaterial(taskId,
                new ArtifactVideoMaterialService.Submission(modified, sha256(canonical(modified)))))
                .hasMessageContaining("不同");
        Map<String, Object> plan = Map.of("schema_version", "video-knowledge-plan-v1",
                "bundle_content_digest", receipt.contentDigest(), "bvid", "BV1234567890",
                "part", 2, "duration_ms", 5000,
                "nodes", List.of(Map.ofEntries(Map.entry("node_id", "n1"),
                        Map.entry("parent_id", ""), Map.entry("kind", "TOPIC"),
                        Map.entry("title", "缺少资料"), Map.entry("start_ms", 0),
                        Map.entry("end_ms", 5000), Map.entry("transcript_segment_ids", List.of()),
                        Map.entry("frame_ids", List.of()), Map.entry("terms", List.of()),
                        Map.entry("claims", List.of()),
                        Map.entry("missing", List.of("NO_SUBTITLE", "NO_FRAMES")))));
        jdbc.update("update workspace_member set status = 'SUSPENDED' where workspace_id = ? and user_id = ?",
                workspaceId, "local-user");
        assertThatThrownBy(() -> videoMaterials.submitParentKnowledgePlan(taskId,
                new ArtifactVideoMaterialService.KnowledgeSubmission(
                        receipt.id(), plan, sha256(canonical(plan)))))
                .hasMessageContaining("Workspace");
        jdbc.update("update workspace_member set status = 'ACTIVE' where workspace_id = ? and user_id = ?",
                workspaceId, "local-user");
        var knowledge = videoMaterials.submitParentKnowledgePlan(taskId,
                new ArtifactVideoMaterialService.KnowledgeSubmission(
                        receipt.id(), plan, sha256(canonical(plan))));
        assertThat(videoMaterials.submitParentKnowledgePlan(taskId,
                new ArtifactVideoMaterialService.KnowledgeSubmission(
                        receipt.id(), plan, sha256(canonical(plan)))).id()).isEqualTo(knowledge.id());
        assertThatThrownBy(() -> requests.markMaterialReady(workspaceId,
                parent.requestId(), receipt.id(), knowledge.id()))
                .hasMessageContaining("Workspace");
        jdbc.update("""
                insert into task_outbox(id, task_id, topic, message_key, payload_json,
                                        status, lease_owner, lease_until)
                values (?, ?, 'noteweave.artifact.job', ?, '{}', 'PROCESSING', ?, ?)
                """, java.util.UUID.randomUUID().toString(), taskId, parent.requestId(),
                "material-delivery", java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(300)));
        assertThatThrownBy(() -> materialTasks.complete(taskId, "material-delivery",
                "wrong-bundle", knowledge.id())).hasMessageContaining("Workspace");
        assertThat(jdbc.queryForObject("select task_status from task where id = ?",
                String.class, taskId)).isEqualTo("RUNNING");
        jdbc.update("update workspace_member set status = 'SUSPENDED' where workspace_id = ? and user_id = ?",
                workspaceId, "local-user");
        assertThatThrownBy(() -> materialTasks.complete(taskId, "material-delivery",
                receipt.id(), knowledge.id()))
                .hasMessageContaining("Workspace");
        assertThat(jdbc.queryForObject("select task_status from task where id = ?",
                String.class, taskId)).isEqualTo("RUNNING");
        jdbc.update("update workspace_member set status = 'ACTIVE' where workspace_id = ? and user_id = ?",
                workspaceId, "local-user");
        assertThat(materialTasks.complete(taskId, "material-delivery",
                receipt.id(), knowledge.id())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select status from task_outbox where task_id = ?",
                String.class, taskId)).isEqualTo("SENT");
        assertThat(requests.view(workspaceId, parent.requestId()).materialState()).isEqualTo("READY");
        assertThat(requests.missingChoices(workspaceId, parent.requestId()))
                .containsExactly("knowledge_blog");
        requests.requestCancellation(workspaceId, parent.requestId(), "local-user");
        assertThat(requests.view(workspaceId, parent.requestId()).materialState()).isEqualTo("READY");
        assertThatThrownBy(() -> videoMaterials.submitParentMaterial(taskId, submission))
                .hasMessageContaining("Workspace");
    }

    private String canonical(Map<String, Object> value) throws Exception {
        return mapper.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,
                true).writeValueAsString(value);
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private VideoLearningRequestDraft draft(List<String> skills) {
        return new VideoLearningRequestDraft("https://www.bilibili.com/video/BV1234567890",
                2, "zh-CN", "STANDARD", "ALLOW", "original-v1", "Study this video", skills);
    }

    private String workspace() throws Exception {
        String json = mvc.perform(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "video-parent-" + System.nanoTime(),
                        "description", "parent request contract"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).path("data").path("workspace_id").asText();
    }
}
