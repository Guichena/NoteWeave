package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.common.Ids;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentCellMergeServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ResearchAgentCellMergeService mergeService;

    private String workspaceId;
    private String taskId;
    private String runId;
    private String cellKey;

    @BeforeEach
    void setUp() {
        workspaceId = Ids.newId();
        taskId = Ids.newId();
        runId = Ids.newId();
        cellKey = "entity-1:method";
        String rowId = Ids.newId();
        String cellId = Ids.newId();

        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "merge-test");
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, taskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status)
                values (?, ?, ?, 'test question', 'DEFAULT', '[]', 'RUNNING')
                """, runId, workspaceId, taskId);
        jdbcTemplate.update("""
                insert into research_row(id, research_run_id, row_key, row_status)
                values (?, ?, 'entity-1', 'CANDIDATE_READY')
                """, rowId, runId);
        jdbcTemplate.update("""
                insert into research_cell(
                    id, research_run_id, research_row_id, cell_key, column_key, candidate_value,
                    cell_status, repair_count, cell_version, plan_revision, entity_set_version,
                    active_task_id, lease_epoch, fencing_token
                ) values (?, ?, ?, ?, 'method', 'old value', 'CANDIDATE_READY', 0, 0, 2, 3, ?, 1, 9)
                """, cellId, runId, rowId, cellKey, taskId);
        jdbcTemplate.update("""
                insert into source_evidence(id, research_run_id, evidence_key, relation_type)
                values (?, ?, 'evidence-1', 'SUPPORTS')
                """, Ids.newId(), runId);
    }

    @Test
    void shouldMergeSupportedCandidateOnceAndReplayIdempotently() {
        String candidateId = Ids.newId();
        ResearchAgentCellMergeService.CandidateReceipt submitted = mergeService.submitCandidate(
                command(candidateId, "candidate-key-1", "new value")
        );
        ResearchAgentCellMergeService.CandidateReceipt candidateReplay = mergeService.submitCandidate(
                command(candidateId, "candidate-key-1", "new value")
        );

        ResearchAgentCellMergeService.MergeResult first = mergeService.mergeCandidate(
                new ResearchAgentCellMergeService.MergeCommand(runId, candidateId, "merge-1", "SUPPORTS")
        );
        ResearchAgentCellMergeService.MergeResult replay = mergeService.mergeCandidate(
                new ResearchAgentCellMergeService.MergeCommand(runId, candidateId, "merge-1", "SUPPORTS")
        );

        assertThat(first.decision()).isEqualTo("ACCEPTED");
        assertThat(submitted.idempotentReplay()).isFalse();
        assertThat(candidateReplay.idempotentReplay()).isTrue();
        assertThat(first.reasonCode()).isEqualTo("VERIFIED_AND_VERSION_MATCHED");
        assertThat(first.resultCellVersion()).isEqualTo(1);
        assertThat(replay.decision()).isEqualTo("IDEMPOTENT_REPLAY");
        assertThat(jdbcTemplate.queryForObject(
                "select cell_version from research_cell where research_run_id = ? and cell_key = ?",
                Integer.class, runId, cellKey)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell_merge where research_run_id = ?",
                Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void shouldRejectLateCandidateWithoutOverwritingNewerCell() {
        String firstCandidate = Ids.newId();
        String lateCandidate = Ids.newId();
        mergeService.submitCandidate(command(firstCandidate, "candidate-key-1", "first value"));
        mergeService.submitCandidate(command(lateCandidate, "candidate-key-2", "late value"));

        ResearchAgentCellMergeService.MergeResult accepted = mergeService.mergeCandidate(
                new ResearchAgentCellMergeService.MergeCommand(runId, firstCandidate, "merge-1", "SUPPORTS")
        );
        ResearchAgentCellMergeService.MergeResult rejected = mergeService.mergeCandidate(
                new ResearchAgentCellMergeService.MergeCommand(runId, lateCandidate, "merge-2", "SUPPORTS")
        );

        assertThat(accepted.decision()).isEqualTo("ACCEPTED");
        assertThat(rejected.decision()).isEqualTo("REJECTED");
        assertThat(rejected.reasonCode()).isEqualTo("STALE_CELL_VERSION");
        assertThat(jdbcTemplate.queryForObject(
                "select candidate_value from research_cell where research_run_id = ? and cell_key = ?",
                String.class, runId, cellKey)).isEqualTo("first value");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell_merge where research_run_id = ?",
                Integer.class, runId)).isEqualTo(2);
    }

    @Test
    void shouldRejectWrongFencingOrVerdictWithoutChangingCanonicalCell() {
        String candidateId = Ids.newId();
        mergeService.submitCandidate(command(candidateId, "candidate-key-fencing", "unsafe value"));
        jdbcTemplate.update("""
                update research_cell set fencing_token = 10
                where research_run_id = ? and cell_key = ?
                """, runId, cellKey);

        ResearchAgentCellMergeService.MergeResult wrongFence = mergeService.mergeCandidate(
                new ResearchAgentCellMergeService.MergeCommand(runId, candidateId, "merge-fence", "SUPPORTS")
        );
        ResearchAgentCellMergeService.MergeResult wrongVerdict = mergeService.mergeCandidate(
                new ResearchAgentCellMergeService.MergeCommand(runId, candidateId, "merge-verdict", "NOT_ENOUGH_INFO")
        );

        assertThat(wrongFence.decision()).isEqualTo("REJECTED");
        assertThat(wrongFence.reasonCode()).isEqualTo("FENCING_TOKEN_MISMATCH");
        assertThat(wrongVerdict.decision()).isEqualTo("REJECTED");
        assertThat(wrongVerdict.reasonCode()).isEqualTo("VERDICT_NOT_SUPPORTS");
        assertThat(jdbcTemplate.queryForObject(
                "select candidate_value from research_cell where research_run_id = ? and cell_key = ?",
                String.class, runId, cellKey)).isEqualTo("old value");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell_merge where research_run_id = ?",
                Integer.class, runId)).isEqualTo(2);
    }

    private ResearchAgentCellMergeService.CandidateCommand command(
            String candidateId,
            String idempotencyKey,
            String value
    ) {
        return new ResearchAgentCellMergeService.CandidateCommand(
                candidateId,
                runId,
                taskId,
                "execution-1",
                idempotencyKey,
                cellKey,
                0,
                2,
                3,
                1,
                9,
                value,
                List.of("evidence-1"),
                0.86
        );
    }
}
