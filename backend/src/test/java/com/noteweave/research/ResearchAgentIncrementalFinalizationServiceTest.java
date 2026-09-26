package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResearchAgentIncrementalFinalizationServiceTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentIncrementalFinalizationService finalization;
    @Autowired private ResearchAgentHonestReportService honestReports;
    @Autowired private MockMvc mockMvc;
    @SpyBean private ResearchAgentIncrementalFinalizationFaultInjector finalizationFaults;
    private String runId;
    private String parentTaskId;
    private String workspaceId;

    @BeforeEach
    void setUp() {
        workspaceId = Ids.newId(); runId = Ids.newId(); parentTaskId = Ids.newId(); String rowId = Ids.newId();
        String agentTaskId = Ids.newId();
        String snapshotKey = "research/external/finalization-test/snapshot-1";
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'finalization-test', 'ACTIVE')", workspaceId);
        jdbcTemplate.update("insert into workspace_member(id, workspace_id, user_id, role, status) values (?, ?, 'local-user', 'OWNER', 'ACTIVE')", Ids.newId(), workspaceId);
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", parentTaskId, workspaceId, runId);
        jdbcTemplate.update("insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode) values (?, ?, ?, 'What is verified?', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')", runId, workspaceId, parentTaskId);
        jdbcTemplate.update("""
                insert into research_agent_task(
                    id, research_run_id, task_key, idempotency_key, wave_no, role, entity_id,
                    branch_id, plan_revision, entity_set_version, target_cells_json, budget_json, status)
                values (?, ?, 'finalization-test-task', 'finalization-test-task', 1, 'DEEP_CELL',
                    'entity-1', 'branch-main', 1, 1, '["entity-1:claim"]', '{}', 'SUBMITTED')
                """, agentTaskId, runId);
        jdbcTemplate.update("""
                insert into research_external_snapshot(
                    id, research_run_id, research_agent_task_id, window_id, source_id, source_title,
                    source_url, source_domain, provider, adapter, snapshot_key, content_text,
                    content_sha256, archive_status)
                values (?, ?, ?, 'external-window-1', 'external:incremental-source', 'Incremental source',
                    'https://example.com/research', 'example.com', 'search-provider', 'external_url', ?,
                    'Verified citation excerpt with archived context.', ?, 'ARCHIVED')
                """, Ids.newId(), runId, agentTaskId, snapshotKey, "c".repeat(64));
        jdbcTemplate.update("insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')", rowId, runId);
        String cellId = Ids.newId();
        String sourceEvidenceId = Ids.newId();
        jdbcTemplate.update("insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, candidate_value, cell_status, evidence_refs_json, repair_count) values (?, ?, ?, 'entity-1:claim', 'claim', 'Verified answer', 'VERIFIED', '[\"evidence-1\"]', 0)", cellId, runId, rowId);
        jdbcTemplate.update("""
                insert into source_evidence(
                    id, research_run_id, evidence_key, window_id, source_id, source_title,
                    source_url, provider, adapter, quote_text, claim_text, snapshot_status, snapshot_key)
                values (?, ?, 'evidence-1', 'external-window-1', 'external:incremental-source',
                    'Incremental source', 'https://example.com/research', 'search-provider', 'external_url',
                    'Verified citation excerpt', 'Verified claim', 'EXTERNAL_ARCHIVED', ?)
                """, sourceEvidenceId, runId, snapshotKey);
        jdbcTemplate.update("insert into research_cell_evidence(id, research_run_id, research_cell_id, source_evidence_id, evidence_key) values (?, ?, ?, ?, 'evidence-1')", Ids.newId(), runId, cellId, sourceEvidenceId);
    }

    @Test
    void shouldFinalizeVerifiedIncrementalLedgerAndReplayTheSameReceipt() {
        var first = finalization.finalizeIncrementalRun(runId);
        var replay = finalization.finalizeIncrementalRun(runId);
        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(first.artifactId()).isEqualTo(replay.artifactId()).isNotBlank();
        assertThat(first.reportDigest()).isEqualTo(replay.reportDigest()).startsWith("sha256:");
        assertThat(first.reportMarkdown()).contains(
                "## Citation audit",
                "Evidence: `evidence-1`",
                "Exact quote: “Verified citation excerpt”",
                "research/external/finalization-test/snapshot-1",
                "https://example.com/research");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_report_artifact where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from research_run where id = ?", String.class, runId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("select task_status from task where id = ?", String.class, parentTaskId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from task_event where task_id = ? and event_type = 'TASK_COMPLETED'",
                Integer.class, parentTaskId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_trace where research_run_id = ? and trace_type = 'INCREMENTAL_FINALIZED'", Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void finalizedIncrementalResearchExposesCitationGatedEvidenceManifest() throws Exception {
        finalization.finalizeIncrementalRun(runId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/evidence",
                        workspaceId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_id").value(runId))
                .andExpect(jsonPath("$.data.evidence.length()").value(1))
                .andExpect(jsonPath("$.data.evidence[0].evidence_id").value("evidence-1"))
                .andExpect(jsonPath("$.data.evidence[0].source_id").value("external:incremental-source"))
                .andExpect(jsonPath("$.data.evidence[0].excerpt").value("Verified citation excerpt"));
    }

    @Test
    void finalizedResearchMaterializesAWorkspaceResearchCollection() throws Exception {
        finalization.finalizeIncrementalRun(runId);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/collection",
                        workspaceId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace_id").value(workspaceId))
                .andExpect(jsonPath("$.data.research_run_id").value(runId))
                .andExpect(jsonPath("$.data.report.markdown", org.hamcrest.Matchers.containsString("Verified answer")))
                .andExpect(jsonPath("$.data.adopted_sources.length()").value(1))
                .andExpect(jsonPath("$.data.adopted_sources[0].source_kind").value("WEB"))
                .andExpect(jsonPath("$.data.adopted_sources[0].source_id").value("external:incremental-source"))
                .andExpect(jsonPath("$.data.adopted_sources[0].source_snapshot_key").value(
                        "research/external/finalization-test/snapshot-1"))
                .andExpect(jsonPath("$.data.adopted_sources[0].source_url").value("https://example.com/research"))
                .andExpect(jsonPath("$.data.adopted_sources[0].source_domain").value("example.com"))
                .andExpect(jsonPath("$.data.adopted_sources[0].excerpt").value("Verified citation excerpt"))
                .andExpect(jsonPath("$.data.notes.length()").value(1))
                .andExpect(jsonPath("$.data.notes[0].note_type").value("FINDING"))
                .andExpect(jsonPath("$.data.notes[0].content").value("Verified answer"));
    }

    @Test
    void idempotentIncrementalFinalizerBackfillsAMissingEvidenceManifest() throws Exception {
        finalization.finalizeIncrementalRun(runId);
        jdbcTemplate.update("delete from research_evidence_manifest_item where manifest_id in (select id from research_evidence_manifest where research_run_id = ?)", runId);
        jdbcTemplate.update("delete from research_evidence_manifest where research_run_id = ?", runId);

        assertThat(finalization.finalizeIncrementalRun(runId).idempotentReplay()).isTrue();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/evidence",
                        workspaceId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence.length()").value(1))
                .andExpect(jsonPath("$.data.evidence[0].evidence_id").value("evidence-1"));
    }

    @Test
    void shouldRenderVerifiedCellsInUnsignedUtf8OrderAndEscapeMarkdownTableContent() {
        String rowId = jdbcTemplate.queryForObject(
                "select id from research_row where research_run_id = ?", String.class, runId);
        jdbcTemplate.update("""
                insert into research_cell(
                    id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, evidence_refs_json, repair_count
                ) values (?, ?, ?, ?, 'claim', ?, 'VERIFIED', ?, 0)
                """, Ids.newId(), runId, rowId, "\uE000", "private|value\\tail\r\nnext", "[\"ev|private\"]");
        jdbcTemplate.update("""
                insert into research_cell(
                    id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, evidence_refs_json, repair_count
                ) values (?, ?, ?, ?, 'claim', ?, 'VERIFIED', ?, 0)
                """, Ids.newId(), runId, rowId, "😀", "emoji", "[\"ev-emoji\"]");

        String report = finalization.finalizeIncrementalRun(runId).reportMarkdown();

        assertThat(report.indexOf("|\uE000|"))
                .isLessThan(report.indexOf("|😀|"));
        assertThat(report).contains("|\uE000|private\\|value\\\\tail<br>next|[\"ev\\|private\"]|");
    }

    @Test
    void shouldRejectUnverifiedLedgerWithoutWritingTerminalState() {
        jdbcTemplate.update("update research_cell set cell_status = 'CANDIDATE_READY' where research_run_id = ?", runId);
        assertThatThrownBy(() -> finalization.finalizeIncrementalRun(runId)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code()).isEqualTo("RESEARCH_AGENT_FINALIZATION_GATE_REJECTED");
        assertThat(jdbcTemplate.queryForObject("select status from research_run where id = ?", String.class, runId)).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject("select task_status from task where id = ?", String.class, parentTaskId)).isEqualTo("RUNNING");
    }

    @Test
    void shouldFinalizeWhenHistoricalFailedTaskWasRepairedIntoVerifiedLedger() {
        jdbcTemplate.update("""
                insert into research_agent_task(
                    id, research_run_id, task_key, idempotency_key, wave_no, role, entity_id,
                    branch_id, plan_revision, entity_set_version, target_cells_json, budget_json,
                    status, terminal_reason, terminal_at)
                values (?, ?, 'failed-before-repair', 'failed-before-repair', 1, 'DEEP_CELL',
                    'entity-1', 'branch-main', 1, 1, '["entity-1:claim"]', '{}',
                    'FAILED', 'NO_SUPPORTED_CANDIDATE', current_timestamp)
                """, Ids.newId(), runId);

        var receipt = finalization.finalizeIncrementalRun(runId);

        assertThat(receipt.idempotentReplay()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_run where id = ?", String.class, runId))
                .isEqualTo("COMPLETED");
    }

    @Test
    void limitationReportShouldTraceAClaimThroughCandidateEvidenceSnapshotAndUrl() {
        String candidateId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_candidate(
                    id, research_run_id, task_id, execution_id, idempotency_key,
                    cell_key, base_cell_version, plan_revision, entity_set_version,
                    lease_epoch, fencing_token, candidate_value, evidence_ids_json, confidence_score)
                values (?, ?, 'task-trace', 'execution-trace', 'candidate-trace',
                    'entity-1:claim', 0, 1, 1, 1, 1, 'Verified answer', '["evidence-1"]', 0.9000)
                """, candidateId, runId);
        jdbcTemplate.update("""
                insert into research_cell_merge(
                    id, research_run_id, candidate_id, merge_key, cell_key,
                    expected_cell_version, result_cell_version, verdict, decision,
                    reason_code, accepted_evidence_ids_json)
                values (?, ?, ?, 'merge-trace', 'entity-1:claim', 0, 1,
                    'ACCEPT', 'ACCEPTED', 'QUALIFIED', '["evidence-1"]')
                """, Ids.newId(), runId, candidateId);
        var decision = new ResearchAgentRunCompletionGate.CompletionDecision(
                ResearchAgentRunCompletionGate.CompletionTerminalState.COMPLETED_WITH_LIMITATIONS,
                java.util.List.of(new ResearchAgentRunCompletionGate.PromotableClaim(
                        "entity-1:claim", "claim", "Verified answer", java.util.List.of("evidence-1"))),
                java.util.List.of(new ResearchAgentRunCompletionGate.UnresolvedCell(
                        "entity-1:limitations", "limitations", "GAP", "OPTIONAL_CELL_UNRESOLVED")),
                java.util.List.of("UNRESOLVED_CELLS_REMAIN"),
                java.util.List.of("UNRESOLVED_CELLS_REMAIN"));

        String report = honestReports.renderAndPersist(runId, decision);

        assertThat(report).contains(
                "COMPLETED_WITH_LIMITATIONS",
                "Verified answer",
                "Cell: `entity-1:claim`",
                "Candidate: `" + candidateId + "`",
                "Evidence: `evidence-1`",
                "Exact quote: “Verified citation excerpt”",
                "research/external/finalization-test/snapshot-1",
                "https://example.com/research",
                "OPTIONAL_CELL_UNRESOLVED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_report_artifact where research_run_id = ?",
                Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void shouldRollbackRunReportAndParentTaskWhenFinalizationFailsBeforeCommit() {
        doThrow(new IllegalStateException("injected finalization crash")).when(finalizationFaults)
                .checkpoint(ResearchAgentIncrementalFinalizationFaultInjector.Stage.AFTER_RUN_REPORT_WRITE);
        assertThatThrownBy(() -> finalization.finalizeIncrementalRun(runId)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("select status from research_run where id = ?", String.class, runId)).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject("select final_report_markdown from research_run where id = ?", String.class, runId)).isNull();
        assertThat(jdbcTemplate.queryForObject("select task_status from task where id = ?", String.class, parentTaskId)).isEqualTo("RUNNING");
        reset(finalizationFaults);
        assertThat(finalization.finalizeIncrementalRun(runId).idempotentReplay()).isFalse();
    }

    @Test
    void shouldLinearizeTwoConcurrentFinalizersIntoOneReceiptAndOneTerminalTrace() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<ResearchAgentIncrementalFinalizationService.FinalizationReceipt> first = CompletableFuture.supplyAsync(() -> awaitAndFinalize(start));
        CompletableFuture<ResearchAgentIncrementalFinalizationService.FinalizationReceipt> second = CompletableFuture.supplyAsync(() -> awaitAndFinalize(start));
        start.countDown();
        var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
        assertThat(a.reportMarkdown()).isEqualTo(b.reportMarkdown());
        assertThat(a.artifactId()).isEqualTo(b.artifactId());
        assertThat(a.idempotentReplay()).isNotEqualTo(b.idempotentReplay());
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_trace where research_run_id = ? and trace_type = 'INCREMENTAL_FINALIZED'", Integer.class, runId)).isEqualTo(1);
    }

    private ResearchAgentIncrementalFinalizationService.FinalizationReceipt awaitAndFinalize(CountDownLatch start) {
        try { start.await(10, TimeUnit.SECONDS); return finalization.finalizeIncrementalRun(runId); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }
}
