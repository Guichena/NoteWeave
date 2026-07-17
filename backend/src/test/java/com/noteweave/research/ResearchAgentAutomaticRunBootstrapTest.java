package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentAutomaticRunBootstrapTest {
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchRunService researchRunService;
    @Autowired private ResearchAgentCoordinatorTickService coordinatorTick;

    @Test
    void shouldCreateAnIncrementalRunWithCanonicalCellsAndTaskizeItsInitialWave() {
        String workspaceId = Ids.newId();
        String fileId = Ids.newId();
        String sourceId = Ids.newId();
        jdbcTemplate.update(
                "insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'agent-bootstrap', 'ACTIVE')",
                workspaceId);
        jdbcTemplate.update(
                "insert into file_object(id, workspace_id, object_key, sha256, file_size) values (?, ?, 'bootstrap.txt', ?, 20)",
                fileId, workspaceId, "c".repeat(64));
        jdbcTemplate.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status)
                values (?, ?, ?, 'Bootstrap source', 'TEXT', 'READY', 'READY', 'READY')
                """, sourceId, workspaceId, fileId);
        String snapshotId = Ids.newId();
        String chunkId = Ids.newId();
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                values (?, ?, ?, 1, 'bootstrap.txt', ?, 'READY', 'READY')
                """, snapshotId, sourceId, fileId, "d".repeat(64));
        jdbcTemplate.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, content, token_estimate)
                values (?, ?, ?, ?, 1, 'Controlled agents isolate evidence and verification responsibilities.', 8)
                """, chunkId, workspaceId, sourceId, snapshotId);
        jdbcTemplate.update("insert into source_window(id, source_chunk_id, window_no, content) values (?, ?, 1, ?)",
                Ids.newId(), chunkId, "Controlled agents isolate evidence and verification responsibilities.");

        ResearchRunResponse created = researchRunService.createRun(workspaceId, new CreateResearchRunRequest(
                "How does controlled multi-agent research improve evidence quality?",
                "DEFAULT", "Produce an evidence-backed technical assessment", "Markdown report",
                List.of("Call out limitations"), null, "DEEP", "TECHNICAL", List.of(sourceId)));

        assertThat(created.status()).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "select agent_execution_mode from research_run where id = ?", String.class, created.researchRunId()))
                .isEqualTo("INCREMENTAL_V1");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from task_outbox where task_id = ?", Integer.class, created.taskId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell where research_run_id = ?", Integer.class, created.researchRunId()))
                .isGreaterThanOrEqualTo(3);

        ResearchAgentCoordinatorTickService.TickReceipt tick =
                coordinatorTick.tick(created.researchRunId(), "bootstrap-test");

        assertThat(tick.outcome()).isEqualTo("INITIAL_WAVE_TASKIZED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_outbox where research_run_id = ?",
                Integer.class, created.researchRunId())).isGreaterThan(0);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task rat
                join research_cell rc on rc.research_run_id = rat.research_run_id
                where rat.research_run_id = ? and rat.branch_id = rc.branch_id
                """, Integer.class, created.researchRunId())).isGreaterThan(0);
    }

    @Test
    void shouldFailFastInsteadOfCreatingAnUnexecutableRunWithoutWorkspaceEvidence() {
        String workspaceId = Ids.newId();
        jdbcTemplate.update(
                "insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'agent-no-scope', 'ACTIVE')",
                workspaceId);

        assertThatThrownBy(() -> researchRunService.createRun(workspaceId, new CreateResearchRunRequest(
                "Research a claim without selected evidence", "DEFAULT", "Find evidence", "Markdown report",
                List.of(), null, "DEEP", "TECHNICAL", List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_SOURCE_SCOPE_REQUIRED");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_run where workspace_id = ?", Integer.class, workspaceId)).isZero();
    }
}
