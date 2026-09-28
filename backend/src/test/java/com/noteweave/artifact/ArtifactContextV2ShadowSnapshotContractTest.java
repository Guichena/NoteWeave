package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.conversation.ContextProjectionV2;
import com.noteweave.memory.ExecutionObservation;
import com.noteweave.memory.MemoryRuntime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "noteweave.context.v2.shadow-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ArtifactContextV2ShadowSnapshotContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryRuntime memoryRuntime;
    @Autowired ArtifactJobService jobs;

    @Test
    void independentArtifactRunsFreezeSeparateContextOnlyWhenShadowEnabled() throws Exception {
        String workspaceId = data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "artifact-shadow",
                        "description", "Artifact Context freeze")))).path("workspace_id").asText();
        String offTask = createJob(workspaceId, "before shadow");
        assertThat(count(offTask)).isZero();
        assertThat(jobs.getWorkerInput(offTask).contextV2Shadow()).isNull();

        data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("mode", "SHADOW"))));
        String firstTask = createJob(workspaceId, "first frozen request");
        String secondTask = createJob(workspaceId, "second frozen request");
        assertThat(count(firstTask)).isEqualTo(1);
        assertThat(count(secondTask)).isEqualTo(1);
        assertFrozen(firstTask, workspaceId, "first frozen request");
        assertFrozen(secondTask, workspaceId, "second frozen request");
        jdbc.update("""
                update artifact_context_v2_shadow_snapshot set projection_sha256 = ?
                where input_snapshot_id = (select input_snapshot_id from artifact_job_run where task_id = ?)
                """, "0".repeat(64), firstTask);
        assertThatThrownBy(() -> jobs.getWorkerInput(firstTask))
                .hasMessageContaining("digest mismatch");
    }

    @Test
    void revokingSelectedMemoryRevisionRedactsArtifactShadow() throws Exception {
        String workspaceId = data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "artifact-shadow-revoke",
                        "description", "Memory revocation")))).path("workspace_id").asText();
        data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("mode", "SHADOW"))));
        var proposal = memoryRuntime.observe(new ExecutionObservation(
                "artifact-shadow-" + System.nanoTime(), workspaceId, "WORKSPACE",
                "preference:artifact-shadow", "Private Artifact Memory sentinel",
                "USER_FEEDBACK", "artifact-shadow-review"));
        data(post("/api/v2/workspaces/{workspaceId}/memory/revisions/{revisionId}/review",
                workspaceId, proposal.revisionId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "ACCEPT"))));
        String taskId = createJob(workspaceId, "Use approved preferences");
        assertThat(jobs.getWorkerInput(taskId).taskId()).isEqualTo(taskId);
        String before = projectionJson(taskId);
        assertThat(mapper.readValue(before, ContextProjectionV2.class).memoryRevisions())
                .extracting(ContextProjectionV2.MemoryRevision::revisionId)
                .contains(proposal.revisionId());
        assertThat(before).contains("Private Artifact Memory sentinel");
        jdbc.update("update memory_item set review_status = 'REVIEW_REQUIRED' where id = ?",
                proposal.memoryItemId());
        data(post("/api/v2/workspaces/{workspaceId}/memory/revisions/{revisionId}/review",
                workspaceId, proposal.revisionId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("decision", "REVOKE"))));
        String after = projectionJson(taskId);
        assertThat(after).doesNotContain("Private Artifact Memory sentinel");
        assertThat(jdbc.queryForObject("""
                select s.status from artifact_context_v2_shadow_snapshot s
                join artifact_job_run r on r.input_snapshot_id = s.input_snapshot_id
                where r.task_id = ?
                """, String.class, taskId)).isEqualTo("REDACTED");
        assertThat(mapper.readValue(after, ContextProjectionV2.class).memoryRevisions().get(0).text())
                .isEmpty();
        assertThatThrownBy(() -> jobs.getWorkerInput(taskId))
                .hasMessageContaining("Memory");
        assertThat(jdbc.queryForObject("""
                select s.replay_availability from artifact_job_run r
                join artifact_run_input_snapshot s on s.id = r.input_snapshot_id
                where r.task_id = ?
                """, String.class, taskId)).isEqualTo("METADATA_ONLY");
        assertThat(jdbc.queryForObject("""
                select s.control_pack_json from artifact_job_run r
                join artifact_run_input_snapshot s on s.id = r.input_snapshot_id
                where r.task_id = ?
                """, String.class, taskId)).isNull();
    }

    private String createJob(String workspaceId, String requirement) throws Exception {
        return data(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspaceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "skill_key", "resume_highlight", "user_requirement", requirement,
                        "inputs", Map.of("language", "zh-CN")))))
                .path("task_id").asText();
    }

    private void assertFrozen(String taskId, String workspaceId, String requirement) throws Exception {
        Map<String, Object> row = jdbc.queryForMap("""
                select s.projection_json, s.projection_sha256, s.status, i.compiler_version,
                       i.user_requirement, i.id as input_snapshot_id
                from artifact_job_run r
                join artifact_run_input_snapshot i on i.id = r.input_snapshot_id
                join artifact_context_v2_shadow_snapshot s on s.input_snapshot_id = i.id
                where r.task_id = ?
                """, taskId);
        String json = (String) row.get("projection_json");
        ContextProjectionV2 projection = mapper.readValue(json, ContextProjectionV2.class);
        assertThat(row.get("status")).isEqualTo("READY");
        assertThat(row.get("user_requirement")).isEqualTo(requirement);
        assertThat((String) row.get("compiler_version")).startsWith("artifact-input-v1@");
        assertThat(row.get("projection_sha256")).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))));
        assertThat(projection.workspaceId()).isEqualTo(workspaceId);
        assertThat(projection.conversationId()).isEmpty();
        assertThat(projection.cutoffSeq()).isZero();
        assertThat(projection.currentInput()).isEqualTo(requirement);
        assertThat(projection.rawTail()).isEmpty();
        ArtifactContextV2ShadowInputResponse workerShadow =
                jobs.getWorkerInput(taskId).contextV2Shadow();
        assertThat(workerShadow).isNotNull();
        assertThat(workerShadow.snapshotId()).isNotBlank();
        assertThat(workerShadow.projectionSha256()).isEqualTo(row.get("projection_sha256"));
        assertThat(workerShadow.compilerVersion()).isEqualTo(projection.compilerVersion());
        assertThat(workerShadow.memoryRevisionIds()).containsExactlyElementsOf(
                projection.memoryRevisions().stream()
                        .map(ContextProjectionV2.MemoryRevision::revisionId).toList());
    }

    private int count(String taskId) {
        return jdbc.queryForObject("""
                select count(*) from artifact_context_v2_shadow_snapshot s
                join artifact_job_run r on r.input_snapshot_id = s.input_snapshot_id
                where r.task_id = ?
                """, Integer.class, taskId);
    }

    private String projectionJson(String taskId) {
        return jdbc.queryForObject("""
                select s.projection_json from artifact_context_v2_shadow_snapshot s
                join artifact_job_run r on r.input_snapshot_id = s.input_snapshot_id
                where r.task_id = ?
                """, String.class, taskId);
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
}
