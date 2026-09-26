package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest(properties = "noteweave.video-learning.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoLearningRequestCreationContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsParentMaterialTaskAndOutboxAtomicallyAndReplaysOneIdentity() throws Exception {
        String workspace = mapper.readTree(mvc.perform(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "video-create-" + System.nanoTime(),
                        "description", "video create contract"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
        var request = new CreateVideoLearningBundleRequest("client-create-1",
                "https://www.bilibili.com/video/BV1234567890", 2, "zh-CN",
                "STANDARD", "ALLOW", "original-v1", "Study this video",
                List.of("knowledge_blog", "video_learning_deck"));
        String path = "/api/v2/workspaces/" + workspace + "/video-learning-bundles";
        String first = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String requestId = mapper.readTree(first).path("data").path("request_id").asText();
        String taskId = mapper.readTree(first).path("data").path("material_task_id").asText();
        assertThat(requestId).isNotBlank();
        assertThat(taskId).isNotBlank();
        assertThat(jdbc.queryForObject("""
                select count(*) from task where id = ? and workspace_id = ?
                  and task_type = 'VIDEO_MATERIAL' and target_id = ?
                """, Integer.class, taskId, workspace, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from task_outbox where task_id = ?
                  and topic = 'noteweave.artifact.job' and status = 'READY'
                """, Integer.class, taskId)).isEqualTo(1);
        String replay = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(replay).path("data").path("request_id").asText())
                .isEqualTo(requestId);
        assertThat(jdbc.queryForObject("""
                select count(*) from task where workspace_id = ? and task_type = 'VIDEO_MATERIAL'
                """, Integer.class, workspace)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from artifact_job where workspace_id = ?
                """, Integer.class, workspace)).isZero();
        assertThat(mapper.readTree(first).path("data").path("choices")).hasSize(2);
    }
}
