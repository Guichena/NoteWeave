package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

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

@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentIncrementalFinalizationServiceTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentIncrementalFinalizationService finalization;
    @SpyBean private ResearchAgentIncrementalFinalizationFaultInjector finalizationFaults;
    private String runId;
    private String parentTaskId;

    @BeforeEach
    void setUp() {
        String workspaceId = Ids.newId(); runId = Ids.newId(); parentTaskId = Ids.newId(); String rowId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'finalization-test', 'ACTIVE')", workspaceId);
        jdbcTemplate.update("insert into task(id, workspace_id, task_type, task_status, target_type, target_id) values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)", parentTaskId, workspaceId, runId);
        jdbcTemplate.update("insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status, agent_execution_mode) values (?, ?, ?, 'What is verified?', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')", runId, workspaceId, parentTaskId);
        jdbcTemplate.update("insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')", rowId, runId);
        jdbcTemplate.update("insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key, candidate_value, cell_status, evidence_refs_json, repair_count) values (?, ?, ?, 'entity-1:claim', 'claim', 'Verified answer', 'VERIFIED', '[\"evidence-1\"]', 0)", Ids.newId(), runId, rowId);
    }

    @Test
    void shouldFinalizeVerifiedIncrementalLedgerAndReplayTheSameReceipt() {
        var first = finalization.finalizeIncrementalRun(runId);
        var replay = finalization.finalizeIncrementalRun(runId);
        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(first.artifactId()).isEqualTo(replay.artifactId()).isNotBlank();
        assertThat(first.reportDigest()).isEqualTo(replay.reportDigest()).startsWith("sha256:");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_agent_report_artifact where research_run_id = ?", Integer.class, runId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from research_run where id = ?", String.class, runId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("select task_status from task where id = ?", String.class, parentTaskId)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_trace where research_run_id = ? and trace_type = 'INCREMENTAL_FINALIZED'", Integer.class, runId)).isEqualTo(1);
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
