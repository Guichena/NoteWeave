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
import com.noteweave.research.ResearchSourceScopeLoader;
import com.noteweave.chat.RetrievalHydrator;
import com.noteweave.chat.NoteRecallRepository;
import com.noteweave.artifact.ArtifactJobService;
import com.noteweave.artifact.ArtifactExportService;
import com.noteweave.artifact.ArtifactKnowledgeWritebackRequest;
import com.noteweave.knowledge.KnowledgeCommandService;
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
    @Autowired ResearchSourceScopeLoader researchSourceScope;
    @Autowired RetrievalHydrator retrievalHydrator;
    @Autowired NoteRecallRepository noteRecallRepository;
    @Autowired ArtifactJobService artifactJobs;
    @Autowired ArtifactExportService artifactExports;
    @Autowired KnowledgeCommandService knowledgeCommands;
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
        mvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs", workspace))
                .andExpect(status().isConflict());
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
        String publishedBody = "# Published private report " + System.nanoTime();
        jdbc.update("""
                update research_run set final_report_title = 'Private report', final_report_markdown = ?
                where id = ?
                """, publishedBody, runId);
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/research-runs/{runId}", workspace, runId))
                .path("final_report_markdown").asText()).isEqualTo(publishedBody);
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/research-runs", workspace)).toString())
                .contains(runId, "Private report");
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
    void sourceCatalogHidesRevokedResearchReportEvenWhenCatalogWasCached() throws Exception {
        String workspace = workspace();
        active(workspace);
        String conversation = conversation(workspace);
        JsonNode receipt = submit(conversation, "仅限本轮研究的内容", "DEEP_RESEARCH");
        String runId = receipt.path("research_run_id").asText();
        String sourceId = Ids.newId();
        String fileId = Ids.newId();
        String sourceSnapshotId = Ids.newId();
        String chunkId = Ids.newId();
        jdbc.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type)
                values (?, ?, ?, ?, 1, 'text/markdown')
                """, fileId, workspace, "test/research/" + sourceId, "0".repeat(64));
        jdbc.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type,
                                   status, parse_status, index_status, generated_by, generated_ref_id)
                values (?, ?, ?, 'Frozen report', 'GENERATED_RESEARCH_REPORT',
                        'READY', 'PARSED', 'INDEXED', 'research_agent', ?)
                """, sourceId, workspace, fileId, runId);
        jdbc.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key,
                                            sha256, parse_status, index_status)
                values (?, ?, ?, 1, ?, ?, 'PARSED', 'INDEXED')
                """, sourceSnapshotId, sourceId, fileId, "test/research/" + sourceId,
                "0".repeat(64));
        jdbc.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id,
                                         chunk_no, content, token_estimate, projection_status)
                values (?, ?, ?, ?, 0, 'Frozen report body', 4, 'PROJECTED')
                """, chunkId, workspace, sourceId, sourceSnapshotId);
        jdbc.update("""
                insert into source_window(id, source_chunk_id, window_no, content)
                values (?, ?, 0, 'Frozen report body')
                """, Ids.newId(), chunkId);
        String citationId = Ids.newId();
        jdbc.update("""
                insert into citation(id, workspace_id, source_id, source_snapshot_id,
                                     source_chunk_id, title, quote_text)
                values (?, ?, ?, ?, ?, 'Frozen report', 'Frozen report body')
                """, citationId, workspace, sourceId, sourceSnapshotId, chunkId);
        jdbc.update("update workspace set source_catalog_version = source_catalog_version + 1 where id = ?",
                workspace);
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/sources", workspace)).toString())
                .contains(sourceId);
        assertThat(researchSourceScope.load(workspace, mapper.writeValueAsString(java.util.List.of(sourceId))))
                .hasSize(1);
        assertThat(retrievalHydrator.hydratePassageOwnership(workspace, java.util.List.of(chunkId)))
                .containsKey(chunkId);
        assertThat(noteRecallRepository.findCurrentSources(workspace))
                .extracting(com.noteweave.chat.NoteRetrievalService.CandidateSource::sourceId)
                .contains(sourceId);
        knowledgeCommands.upsertWikiPage(workspace, "Research-derived Wiki",
                "Frozen report body", java.util.List.of(citationId));
        String historicalAnswerId = Ids.newId();
        int nextMessageSeq = jdbc.queryForObject("""
                select coalesce(max(message_seq), 0) + 1 from conversation_message
                where conversation_id = ?
                """, Integer.class, conversation);
        jdbc.update("""
                insert into conversation_message(id, conversation_id, workspace_id,
                                                 message_seq, role, answer_mode, content)
                values (?, ?, ?, ?, 'ASSISTANT', 'QA', 'Historical cited answer body')
                """, historicalAnswerId, conversation, workspace, nextMessageSeq);
        jdbc.update("""
                insert into message_citation(id, message_id, citation_id, sort_order)
                values (?, ?, ?, 0)
                """, Ids.newId(), historicalAnswerId, citationId);
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                workspace, conversation)).toString()).contains("Historical cited answer body");
        JsonNode beforeWikiStats = data(get("/api/v2/workspaces/{workspaceId}/wiki-stats", workspace));
        assertThat(beforeWikiStats.path("page_count").asInt()).isEqualTo(1);
        assertThat(beforeWikiStats.path("citation_count").asInt()).isEqualTo(1);
        JsonNode beforeWikiIndex = data(get("/api/v2/workspaces/{workspaceId}/wiki-index", workspace));
        assertThat(beforeWikiIndex.path("source_backed_page_count").asInt()).isEqualTo(1);
        assertThat(beforeWikiIndex.path("ready_source_count").asInt()).isEqualTo(1);
        String artifactTaskId = data(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of(
                        "skill_key", "resume_highlight", "user_requirement", "Summarize report",
                        "inputs", Map.of("language", "zh-CN"),
                        "source_scope_source_ids", java.util.List.of(sourceId)))))
                .path("task_id").asText();
        assertThat(artifactJobs.getWorkerInput(artifactTaskId).sourceScope())
                .extracting(com.noteweave.worker.WorkerSourceScopeItemResponse::sourceId)
                .containsExactly(sourceId);
        String artifactJobId = jdbc.queryForObject("""
                select artifact_job_id from artifact_job_run where task_id = ?
                """, String.class, artifactTaskId);
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, skill_key, version_no, title,
                                             content_markdown, origin_task_id)
                values (?, ?, 'resume_highlight', 1, 'Derived version', '# Derived report', ?)
                """, Ids.newId(), artifactJobId, artifactTaskId);
        assertThat(artifactJobs.getVersionDetail(workspace, artifactJobId, 1).contentMarkdown())
                .contains("Derived report");
        assertThat(artifactJobs.listVersions(workspace, artifactJobId))
                .extracting(com.noteweave.artifact.ArtifactVersionSummaryResponse::title)
                .containsExactly("Derived version");

        String originalSnapshot = jdbc.queryForObject("""
                select snapshot_json from run_input_snapshot where research_run_id = ?
                """, String.class, runId);
        JsonNode damaged = mapper.readTree(originalSnapshot);
        ((com.fasterxml.jackson.databind.node.ObjectNode) damaged)
                .put("context_v2_projection_sha256", "0".repeat(64));
        jdbc.update("update run_input_snapshot set snapshot_json = ? where research_run_id = ?",
                mapper.writeValueAsString(damaged), runId);
        mvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspace))
                .andExpect(status().isConflict());
        assertThatThrownBy(() -> researchSourceScope.load(workspace,
                mapper.writeValueAsString(java.util.List.of(sourceId))))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
        assertThatThrownBy(() -> retrievalHydrator.hydratePassageOwnership(
                workspace, java.util.List.of(chunkId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
        assertThatThrownBy(() -> artifactJobs.getWorkerInput(artifactTaskId))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
        assertThatThrownBy(() -> artifactJobs.getVersionDetail(workspace, artifactJobId, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
        assertThatThrownBy(() -> artifactJobs.listVersions(workspace, artifactJobId))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_CONTEXT_SNAPSHOT_MISMATCH"));
        jdbc.update("update run_input_snapshot set snapshot_json = ? where research_run_id = ?",
                originalSnapshot, runId);

        mvc.perform(delete("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                workspace, conversation, receipt.path("message_id").asText()))
                .andExpect(status().isOk());
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/sources", workspace)).toString())
                .doesNotContain(sourceId, "Frozen report");
        assertThat(data(get("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                workspace, conversation)).toString())
                .doesNotContain("Historical cited answer body")
                .contains("\"context_status\":\"REDACTED\"");
        JsonNode afterWikiStats = data(get("/api/v2/workspaces/{workspaceId}/wiki-stats", workspace));
        assertThat(afterWikiStats.path("page_count").asInt()).isZero();
        assertThat(afterWikiStats.path("citation_count").asInt()).isZero();
        JsonNode afterWikiIndex = data(get("/api/v2/workspaces/{workspaceId}/wiki-index", workspace));
        assertThat(afterWikiIndex.path("source_backed_page_count").asInt()).isZero();
        assertThat(afterWikiIndex.path("ready_source_count").asInt()).isZero();
        assertThatThrownBy(() -> researchSourceScope.load(workspace,
                mapper.writeValueAsString(java.util.List.of(sourceId))))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_SOURCE_CONTEXT_REDACTED"));
        assertThat(retrievalHydrator.hydratePassageOwnership(workspace, java.util.List.of(chunkId)))
                .isEmpty();
        assertThat(retrievalHydrator.hydrateNoteWindows(workspace, java.util.List.of(sourceId)))
                .isEmpty();
        assertThat(noteRecallRepository.findCurrentSources(workspace))
                .extracting(com.noteweave.chat.NoteRetrievalService.CandidateSource::sourceId)
                .doesNotContain(sourceId);
        assertThatThrownBy(() -> knowledgeCommands.upsertWikiPage(workspace, "Revoked Wiki",
                "Frozen report body", java.util.List.of(citationId)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("KNOWLEDGE_CITATION_REVOKED"));
        assertThatThrownBy(() -> artifactJobs.getWorkerInput(artifactTaskId))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_SOURCE_CONTEXT_REDACTED"));
        assertThatThrownBy(() -> artifactJobs.getVersionDetail(workspace, artifactJobId, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_SOURCE_CONTEXT_REDACTED"));
        assertThat(artifactJobs.listVersions(workspace, artifactJobId)).isEmpty();
        assertThatThrownBy(() -> artifactExports.downloadFile(workspace, artifactJobId, 1, "no-file"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_SOURCE_CONTEXT_REDACTED"));
        assertThatThrownBy(() -> artifactExports.listVersionFiles(workspace, artifactJobId, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_SOURCE_CONTEXT_REDACTED"));
        assertThatThrownBy(() -> artifactJobs.saveVersionAsSource(workspace, artifactJobId, 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_SOURCE_CONTEXT_REDACTED"));
        assertThatThrownBy(() -> artifactJobs.writeVersionToKnowledge(workspace, artifactJobId, 1,
                new ArtifactKnowledgeWritebackRequest("NOTE", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RESEARCH_SOURCE_CONTEXT_REDACTED"));
        mvc.perform(post("/api/v2/workspaces/{workspaceId}/artifact-jobs", workspace)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of(
                        "skill_key", "resume_highlight", "user_requirement", "Retry report",
                        "inputs", Map.of("language", "zh-CN"),
                        "source_scope_source_ids", java.util.List.of(sourceId)))))
                .andExpect(status().isConflict());
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
