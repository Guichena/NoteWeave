package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ResearchRunService researchRunService;
    @Autowired private ResearchAgentCoordinatorTickService coordinatorTick;
    @Autowired private ResearchAgentTaskService taskService;

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

        String secondFileId = Ids.newId();
        String secondSourceId = Ids.newId();
        jdbcTemplate.update(
                "insert into file_object(id, workspace_id, object_key, sha256, file_size) values (?, ?, 'bootstrap-2.txt', ?, 20)",
                secondFileId, workspaceId, "e".repeat(64));
        jdbcTemplate.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status)
                values (?, ?, ?, 'Bootstrap source 2', 'TEXT', 'READY', 'READY', 'READY')
                """, secondSourceId, workspaceId, secondFileId);
        String secondSnapshotId = Ids.newId();
        String secondChunkId = Ids.newId();
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key, sha256, parse_status, index_status)
                values (?, ?, ?, 1, 'bootstrap-2.txt', ?, 'READY', 'READY')
                """, secondSnapshotId, secondSourceId, secondFileId, "f".repeat(64));
        jdbcTemplate.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id, chunk_no, content, token_estimate)
                values (?, ?, ?, ?, 1, 'Selected sources are optional seeds for web research.', 8)
                """, secondChunkId, workspaceId, secondSourceId, secondSnapshotId);
        jdbcTemplate.update("insert into source_window(id, source_chunk_id, window_no, content) values (?, ?, 1, ?)",
                Ids.newId(), secondChunkId, "Selected sources are optional seeds for web research.");

        ResearchRunResponse created = researchRunService.createRun(workspaceId, new CreateResearchRunRequest(
                "How does controlled multi-agent research improve evidence quality?",
                "DEFAULT", "Produce an evidence-backed technical assessment", "Markdown report",
                List.of("Call out limitations"), null, "DEEP", "TECHNICAL", List.of(sourceId, secondSourceId)));

        assertThat(created.status()).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "select agent_execution_mode from research_run where id = ?", String.class, created.researchRunId()))
                .isEqualTo("INCREMENTAL_V1");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from task_outbox where task_id = ?", Integer.class, created.taskId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell where research_run_id = ?", Integer.class, created.researchRunId()))
                .isGreaterThanOrEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                select high_risk from research_cell
                where research_run_id = ? and column_key = 'answer'
                """, Boolean.class, created.researchRunId())).isFalse();

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
    void shouldCreateAndTaskizeWebResearchWithoutWorkspaceEvidence() throws Exception {
        String workspaceId = Ids.newId();
        jdbcTemplate.update(
                "insert into workspace(id, owner_id, name, status) values (?, 'local-user', 'agent-no-scope', 'ACTIVE')",
                workspaceId);

        ResearchRunResponse created = researchRunService.createRun(workspaceId, new CreateResearchRunRequest(
                "Research a claim without selected evidence", "DEFAULT", "Find evidence", "Markdown report",
                List.of(), null, "DEEP", "TECHNICAL", List.of()));

        assertThat(created.status()).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "select workspace_id from research_run where id = ?", String.class, created.researchRunId()))
                .isEqualTo(workspaceId);
        assertThat(jdbcTemplate.queryForObject(
                "select source_scope_json from research_run where id = ?", String.class, created.researchRunId()))
                .isEqualTo("[]");

        ResearchAgentCoordinatorTickService.TickReceipt tick =
                coordinatorTick.tick(created.researchRunId(), "web-only-bootstrap-test");

        assertThat(tick.outcome()).isEqualTo("INITIAL_WAVE_TASKIZED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_task where research_run_id = ?",
                Integer.class, created.researchRunId())).isGreaterThan(0);
        String agentTaskId = jdbcTemplate.queryForObject("""
                select id from research_agent_task where research_run_id = ? order by task_key limit 1
                """, String.class, created.researchRunId());
        ResearchAgentTaskService.ClaimedTask claimed = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(agentTaskId, "web-only-worker", 30));
        JsonNode snapshot = objectMapper.readTree(claimed.taskSnapshotJson());
        assertThat(snapshot.path("workspace_id").asText()).isEqualTo(workspaceId);
        assertThat(snapshot.path("source_policy").path("source_scope").isArray()).isTrue();
        assertThat(snapshot.path("source_policy").path("source_scope")).isEmpty();
        assertThat(snapshot.path("source_policy").path("allow_external_search").asBoolean()).isTrue();
        assertThat(snapshot.path("source_policy").path("allow_external_fetch").asBoolean()).isTrue();
    }
}
