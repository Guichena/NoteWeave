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
                insert into task_outbox(id, task_id, topic, message_key, payload_json, status)
                values (?, ?, 'noteweave.video.material', ?, '{}', 'PROCESSING')
                """, java.util.UUID.randomUUID().toString(), taskId, taskId);

        var cancelled = requests.requestCancellation(workspaceId, parent.requestId(), "local-user");
        assertThat(cancelled.cancellationRequested()).isTrue();
        assertThat(cancelled.materialState()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select task_status from task where id = ?",
                String.class, taskId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select status from task_outbox where task_id = ?",
                String.class, taskId)).isEqualTo("PROCESSING");
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
