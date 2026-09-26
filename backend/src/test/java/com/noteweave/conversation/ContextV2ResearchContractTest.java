package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.research.ResearchBriefCompiler;
import com.noteweave.research.ResearchAgentTaskCoordinatorService;
import com.noteweave.research.ResearchAgentTaskService;
import com.noteweave.research.ResearchRunCommandService;
import com.noteweave.storage.ObjectStorage;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "noteweave.context.v2.active-enabled=true",
        "noteweave.context.v2.research-active-enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContextV2ResearchContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ResearchBriefCompiler briefs;
    @Autowired ResearchAgentTaskCoordinatorService coordinator;
    @Autowired ResearchAgentTaskService tasks;
    @Autowired ResearchRunCommandService researchRuns;
    @Autowired ObjectStorage storage;
    @Autowired MemoryRuntime memory;
    @SpyBean ConversationContextCompilerV2Service compiler;

    @Test
    void researchUsesOneFrozenProjectionForMatrixTasksAndRunSnapshot() throws Exception {
        String workspace = workspace();
        active(workspace);
        String conversation = conversation(workspace);
        String earlier = "研究背景：OpenSERP 的索引延迟";
        JsonNode first = submit(conversation, earlier, "QA");
        String current = "继续研究部署权衡";
        JsonNode receipt = submit(conversation, current, "DEEP_RESEARCH");
        String runId = receipt.path("research_run_id").asText();
        var run = jdbc.queryForMap("""
                select question, execution_question, context_snapshot_id
                from research_run where id = ?
                """, runId);
        assertThat(run.get("question")).isEqualTo(current);
        assertThat((String) run.get("execution_question")).contains(current, earlier);
        String snapshotId = (String) run.get("context_snapshot_id");
        assertThat(snapshotId).isNotBlank();
        var snapshot = jdbc.queryForMap("""
                select id, compiler_version, snapshot_json from run_input_snapshot
                where research_run_id = ?
                """, runId);
        assertThat(snapshot.get("id")).isEqualTo(snapshotId);
        assertThat(snapshot.get("compiler_version")).isEqualTo(ContextWindowPlannerV2.COMPILER_VERSION);
        JsonNode frozen = mapper.readTree((String) snapshot.get("snapshot_json"));
        assertThat(frozen.path("context_v2_projection").path("raw_tail").toString())
                .contains(first.path("message_id").asText(), earlier);
        assertThat(frozen.path("context_v2_projection_sha256").asText()).hasSize(64);

        jdbc.update("update conversation_message set content = ? where id = ?",
                "tampered after freeze", first.path("message_id").asText());
        var brief = briefs.compile(runId, (String) run.get("execution_question"));
        assertThat(brief.planningQuery()).contains(earlier).doesNotContain("tampered after freeze");
        assertThat(brief.researchBrief().get("context_snapshot_id")).isEqualTo(snapshotId);
        coordinator.planAndEnqueue(runId);
        String taskId = jdbc.queryForObject("""
                select id from research_agent_task where research_run_id = ? order by task_key limit 1
                """, String.class, runId);
        JsonNode taskSnapshot = mapper.readTree(tasks.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "frozen-worker", 30))
                .taskSnapshotJson());
        assertThat(taskSnapshot.path("query_policy").path("query").asText())
                .contains(earlier, current).doesNotContain("tampered after freeze");
    }

    @Test
    void compilerFailureFallsBackToOriginalQuestionAndRecordsReason() throws Exception {
        String workspace = workspace();
        active(workspace);
        String conversation = conversation(workspace);
        doThrow(new BusinessException("CONTEXT_BUDGET_EXCEEDED", "fixed test failure"))
                .when(compiler).compile(anyString(), anyString(), anyString(), anyInt(),
                        anyString(), anyString(), anyInt());
        JsonNode receipt = submit(conversation, "研究缓存一致性", "DEEP_RESEARCH");
        String runId = receipt.path("research_run_id").asText();
        var run = jdbc.queryForMap("""
                select question, execution_question, context_snapshot_id from research_run where id = ?
                """, runId);
        assertThat(run.get("execution_question")).isNull();
        assertThat(run.get("context_snapshot_id")).isNull();
        assertThat(briefs.compile(runId, (String) run.get("question")).planningQuery())
                .contains("研究缓存一致性");
        var snapshot = jdbc.queryForMap("""
                select compiler_version, snapshot_json from run_input_snapshot where research_run_id = ?
                """, runId);
        assertThat(snapshot.get("compiler_version")).isEqualTo("research-input-v1");
        assertThat(mapper.readTree((String) snapshot.get("snapshot_json"))
                .path("context_v2_fallback_code").asText()).isEqualTo("CONTEXT_BUDGET_EXCEEDED");
    }

    @Test
    void changedFrozenDigestBlocksResearchPlanning() throws Exception {
        String workspace = workspace();
        active(workspace);
        String conversation = conversation(workspace);
        JsonNode receipt = submit(conversation, "研究事务边界", "DEEP_RESEARCH");
        String runId = receipt.path("research_run_id").asText();
        String executionQuestion = jdbc.queryForObject("""
                select execution_question from research_run where id = ?
                """, String.class, runId);
        String snapshotJson = jdbc.queryForObject("""
                select snapshot_json from run_input_snapshot where research_run_id = ?
                """, String.class, runId);
        JsonNode snapshot = mapper.readTree(snapshotJson);
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot)
                .put("context_v2_projection_sha256", "0".repeat(64));
        jdbc.update("update run_input_snapshot set snapshot_json = ? where research_run_id = ?",
                mapper.writeValueAsString(snapshot), runId);
        assertThatThrownBy(() -> briefs.compile(runId, executionQuestion))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
    }

    @Test
    void deletingSelectedMessageRedactsProjectionAndBlocksPlanning() throws Exception {
        String workspace = workspace();
        active(workspace);
        String conversation = conversation(workspace);
        String privateText = "仅内部使用的研究问题-" + System.nanoTime();
        JsonNode receipt = submit(conversation, privateText, "DEEP_RESEARCH");
        String runId = receipt.path("research_run_id").asText();
        String executionQuestion = jdbc.queryForObject("""
                select execution_question from research_run where id = ?
                """, String.class, runId);
        coordinator.planAndEnqueue(runId);
        String taskId = jdbc.queryForObject("""
                select id from research_agent_task where research_run_id = ? order by task_key limit 1
                """, String.class, runId);
        var claimed = tasks.claimTask(new ResearchAgentTaskService.ClaimCommand(
                taskId, "pre-delete-worker", 30));
        mvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                workspace, conversation, receipt.path("message_id").asText()))
                .andExpect(status().isOk());
        var snapshot = jdbc.queryForMap("""
                select snapshot_json, replay_availability from run_input_snapshot where research_run_id = ?
                """, runId);
        assertThat(snapshot.get("replay_availability")).isEqualTo("METADATA_ONLY");
        assertThat((String) snapshot.get("snapshot_json")).doesNotContain(privateText);
        assertThatThrownBy(() -> briefs.compile(runId, executionQuestion))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
        assertThatThrownBy(() -> tasks.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "redacted-worker", 30)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
        assertThatThrownBy(() -> tasks.heartbeat(new ResearchAgentTaskService.LeaseCommand(
                taskId, "pre-delete-worker", claimed.leaseEpoch(), claimed.fencingToken(), 30)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
        mvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{runId}", workspace, runId))
                .andExpect(status().isConflict());
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/research-runs", workspace)).toString())
                .doesNotContain(runId);
    }

    @Test
    void checkpointResumeCopiesTheSameFrozenProjectionWithANewRunIdentity() throws Exception {
        String workspace = workspace();
        active(workspace);
        String conversation = conversation(workspace);
        JsonNode receipt = submit(conversation, "比较缓存一致性策略", "DEEP_RESEARCH");
        String originalRun = receipt.path("research_run_id").asText();
        String originalSnapshotId = jdbc.queryForObject("""
                select context_snapshot_id from research_run where id = ?
                """, String.class, originalRun);
        String objectKey = "workspace/%s/research/%s/checkpoints/1.json"
                .formatted(workspace, originalRun);
        byte[] bytes = "{\"checkpoint_no\":1}".getBytes(StandardCharsets.UTF_8);
        storage.write("noteweave-derived", objectKey, bytes);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        jdbc.update("""
                insert into research_execution_checkpoint(
                    id, research_run_id, checkpoint_no, snapshot_type, object_key,
                    payload_sha256, content_size, active_branch_key, final_loop_decision, summary_json
                ) values (?, ?, 1, 'LOOP_END', ?, ?, ?, 'branch-main', 'CONTINUE', '{}')
                """, Ids.newId(), originalRun, objectKey, digest, bytes.length);

        String resumedRun = researchRuns.resumeFromCheckpoint(workspace, originalRun, 1,
                "CONTEXT_RESTART").researchRunId();
        var resumed = jdbc.queryForMap("""
                select question, execution_question, context_snapshot_id
                from research_run where id = ?
                """, resumedRun);
        String copiedId = (String) resumed.get("context_snapshot_id");
        assertThat(copiedId).isNotEqualTo(originalSnapshotId);
        assertThat(resumed.get("question")).isEqualTo("比较缓存一致性策略");
        assertThat(resumed.get("execution_question")).isEqualTo(jdbc.queryForObject("""
                select execution_question from research_run where id = ?
                """, String.class, originalRun));
        assertThat(jdbc.queryForObject("""
                select snapshot_json from run_input_snapshot where id = ? and research_run_id = ?
                """, String.class, copiedId, resumedRun)).isEqualTo(jdbc.queryForObject("""
                select snapshot_json from run_input_snapshot where id = ?
                """, String.class, originalSnapshotId));
        assertThat(briefs.compile(resumedRun, (String) resumed.get("execution_question"))
                .researchBrief().get("context_snapshot_id")).isEqualTo(copiedId);
    }

    @Test
    void revokingSelectedMemoryRedactsResearchProjectionAndBlocksNewTasks() throws Exception {
        String workspace = workspace();
        active(workspace);
        String privateMemory = "Private research preference " + System.nanoTime();
        var proposal = memory.observe(new ExecutionObservation(
                "research-memory-" + System.nanoTime(), workspace, "WORKSPACE",
                "preference:research", privateMemory, "USER_FEEDBACK", "research-review"));
        data(post("/api/v2/workspaces/{workspaceId}/memory/revisions/{revisionId}/review",
                workspace, proposal.revisionId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("decision", "ACCEPT"))));
        String conversation = conversation(workspace);
        JsonNode receipt = submit(conversation, "研究缓存策略", "DEEP_RESEARCH");
        String runId = receipt.path("research_run_id").asText();
        String before = jdbc.queryForObject("""
                select snapshot_json from run_input_snapshot where research_run_id = ?
                """, String.class, runId);
        assertThat(before).contains(privateMemory, proposal.revisionId());

        jdbc.update("update memory_item set review_status = 'REVIEW_REQUIRED' where id = ?",
                proposal.memoryItemId());
        data(post("/api/v2/workspaces/{workspaceId}/memory/revisions/{revisionId}/review",
                workspace, proposal.revisionId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("decision", "REVOKE"))));
        var after = jdbc.queryForMap("""
                select snapshot_json, replay_availability from run_input_snapshot where research_run_id = ?
                """, runId);
        assertThat(after.get("replay_availability")).isEqualTo("METADATA_ONLY");
        assertThat((String) after.get("snapshot_json"))
                .contains(proposal.revisionId()).doesNotContain(privateMemory);
        assertThatThrownBy(() -> coordinator.planAndEnqueue(runId))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
    }

    private String workspace() throws Exception {
        return data(post("/api/v2/workspaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("name", "research-v2-" + System.nanoTime(),
                        "description", "Research frozen Context contract"))))
                .path("workspace_id").asText();
    }

    private String conversation(String workspace) throws Exception {
        return data(post("/api/v2/workspaces/{workspaceId}/conversations", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("title", "Research v2",
                        "conversation_type", "WORKSPACE_CHAT"))))
                .path("conversation_id").asText();
    }

    private void active(String workspace) throws Exception {
        data(put("/api/v2/workspaces/{workspaceId}/context-v2-rollout", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("mode", "ACTIVE"))));
    }

    private JsonNode submit(String conversation, String input, String mode) throws Exception {
        return data(post("/api/v2/conversations/{conversationId}/messages", conversation)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("content", input, "answer_mode", mode,
                        "client_request_id", "research-v2-" + System.nanoTime()))));
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
}
