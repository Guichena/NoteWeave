package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.memory.ExecutionObservation;
import com.noteweave.memory.MemoryRuntime;
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
        "noteweave.context.v2.active-enabled=true",
        "noteweave.context.v2.artifact-active-enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ArtifactContextV2ActiveContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryRuntime memoryRuntime;
    @Autowired ArtifactJobService jobs;

    @Test
    void activeRunConsumesOnlyItsFrozenProjectionAndFailsClosedWhenRedacted() throws Exception {
        String workspaceId = data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "artifact-v2-active",
                        "description", "Frozen Artifact consumption"))))
                .path("workspace_id").asText();
        data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("mode", "ACTIVE"))));
        var proposal = memoryRuntime.observe(new ExecutionObservation(
                "artifact-active-" + System.nanoTime(), workspaceId, "WORKSPACE",
                "preference:artifact-active", "Use brief headings in the output",
                "USER_FEEDBACK", "artifact-active-review"));
        data(post("/api/v2/workspaces/{workspaceId}/memory/revisions/{revisionId}/review",
                workspaceId, proposal.revisionId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "ACCEPT"))));

        String taskId = createJob(workspaceId, "Summarize the design");
        ArtifactWorkerInputResponse input = jobs.getWorkerInput(taskId);
        assertThat(input.inputPayload().userRequirement()).isEqualTo("Summarize the design");
        assertThat(input.inputPayload().generationBrief())
                .contains("Summarize the design", "Use brief headings in the output")
                .contains("事实与引用仍须来自 Source");
        assertThat(input.contextV2Shadow().replayAvailability()).isEqualTo("FULL");
        assertThat(jdbc.queryForObject("""
                select s.consumption_mode from artifact_context_v2_shadow_snapshot s
                join artifact_job_run r on r.input_snapshot_id = s.input_snapshot_id
                where r.task_id = ?
                """, String.class, taskId)).isEqualTo("ACTIVE");

        data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("mode", "OFF"))));
        assertThat(jobs.getWorkerInput(taskId).inputPayload().generationBrief())
                .contains("Use brief headings in the output");
        String laterTask = createJob(workspaceId, "New v1 request");
        assertThat(jobs.getWorkerInput(laterTask).inputPayload().generationBrief())
                .isEqualTo("New v1 request");

        jdbc.update("""
                update artifact_context_v2_shadow_snapshot set status = 'REDACTED'
                where input_snapshot_id = (select input_snapshot_id from artifact_job_run where task_id = ?)
                """, taskId);
        assertThatThrownBy(() -> jobs.getWorkerInput(taskId))
                .hasMessageContaining("Frozen Artifact Context is unavailable");
    }

    private String createJob(String workspaceId, String requirement) throws Exception {
        return data(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "skill_key", "resume_highlight", "user_requirement", requirement,
                        "inputs", Map.of("language", "zh-CN")))))
                .path("task_id").asText();
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
}
