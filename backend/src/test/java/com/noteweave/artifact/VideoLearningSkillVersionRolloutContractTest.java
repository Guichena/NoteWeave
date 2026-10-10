package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

@SpringBootTest(properties = {
        "noteweave.video-learning.enabled=true",
        "noteweave.video-learning.blocked-skill-versions=knowledge_blog@1.0.0"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoLearningSkillVersionRolloutContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void blockedPublishedVersionCannotCreateParentButSiblingVersionCan() throws Exception {
        String workspace = mapper.readTree(mvc.perform(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "skill-rollout-" + System.nanoTime(),
                        "description", "version rollout contract"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
        String path = "/api/v2/workspaces/" + workspace + "/video-learning-bundles";
        var overview = mapper.readTree(mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(overview.path("enabled").asBoolean()).isTrue();
        assertThat(overview.path("available_skills").toString())
                .doesNotContain("knowledge_blog").contains("interview_qa");
        var blocked = request("blocked", "knowledge_blog");
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(blocked)))
                .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("select count(*) from video_learning_request where workspace_id = ?",
                Integer.class, workspace)).isZero();
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request("allowed", "interview_qa"))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from video_learning_request where workspace_id = ?",
                Integer.class, workspace)).isEqualTo(1);
    }

    private CreateVideoLearningBundleRequest request(String id, String skill) {
        return new CreateVideoLearningBundleRequest(id,
                "https://www.bilibili.com/video/BV1234567890", 1, "zh-CN",
                "STANDARD", "ALLOW", "original-v1", "Study this video", List.of(skill));
    }
}
