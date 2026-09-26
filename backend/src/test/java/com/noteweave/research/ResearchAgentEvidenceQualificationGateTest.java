package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DR-301 GATING regression, run with strict evidence validation globally enabled.
 *
 * <p>The per-candidate hard gate introduced by DR-301 must not soften the pre-existing,
 * stricter GATING mode: when {@code strict-research-evidence-validation} and the per-Run
 * {@code STRICT_EVIDENCE} snapshot flag are both on, a single rejected binding still fails
 * the whole completion with the unchanged {@code RESEARCH_EVIDENCE_QUALIFICATION_FAILED}
 * error code. A separate context is required because both conditions are captured at bean
 * construction time.</p>
 */
@SpringBootTest(properties = "noteweave.research.strict-research-evidence-validation=true")
@ActiveProfiles("test")
class ResearchAgentEvidenceQualificationGateTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentCommandOutboxService outboxService;
    @Autowired private ResearchBudgetAndCheckpointService budgetService;
    @Autowired private ResearchAgentCompletionService completionService;
    @Autowired private ResearchAgentCompletionCanonicalizer canonicalizer;
    @Autowired private ResearchAgentFeatureFlagService featureFlags;

    @Test
    void shouldFailTheWholeCompletionWhenGatingModeFindsARejectedCandidate() {
        Fixture fixture = fixture(true);
        assertThat(featureFlags.currentEnabled(ResearchAgentFeatureFlagService.STRICT_EVIDENCE)).isTrue();
        assertThat(featureFlags.enabledForRun(fixture.runId(), ResearchAgentFeatureFlagService.STRICT_EVIDENCE))
                .isTrue();

        assertThatThrownBy(() -> completionService.complete(
                fixture.taskId(), contradictedEnvelope(fixture)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_EVIDENCE_QUALIFICATION_FAILED");

        assertThat(count("research_agent_completion", "research_agent_task_id", fixture.taskId())).isZero();
        assertThat(count("research_agent_execution", "research_agent_task_id", fixture.taskId())).isZero();
        assertThat(count("research_evidence_validation", "research_run_id", fixture.runId())).isZero();
        assertThat(count("research_cell_merge", "research_run_id", fixture.runId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select cell_version from research_cell where research_run_id = ? and cell_key = ?",
                Integer.class, fixture.runId(), fixture.cellKeys().get(0))).isZero();
    }

    @Test
    void shouldStillCommitAQualifiedCandidateWhileGatingIsEnabled() {
        Fixture fixture = fixture(true);

        ResearchAgentCompletionReceipt receipt = completionService.complete(
                fixture.taskId(), qualifiedEnvelope(fixture));

        assertThat(receipt.rejectedMerges()).isEmpty();
        assertThat(receipt.acceptedMerges()).containsExactly(new ResearchAgentCompletionReceipt.MergeReceipt(
                fixture.cellKeys().get(0), 0, 1, "ACCEPTED", "VERIFIED_AND_VERSION_MATCHED"));
        assertThat(jdbcTemplate.queryForObject("""
                select validation_mode from research_evidence_validation
                where completion_id = ? and candidate_id is not null
                """, String.class, receipt.completionId())).isEqualTo("GATING");
    }

    @Test
    void shouldKeepThePerRunFlagAsTheSecondConditionOfGatingMode() {
        Fixture fixture = fixture(false);

        ResearchAgentCompletionReceipt receipt = completionService.complete(
                fixture.taskId(), contradictedEnvelope(fixture));

        assertThat(jdbcTemplate.queryForObject("""
                select validation_mode from research_evidence_validation
                where completion_id = ? and candidate_id is not null
                """, String.class, receipt.completionId())).isEqualTo("SHADOW");
        assertThat(receipt.acceptedMerges()).isEmpty();
        assertThat(receipt.rejectedMerges()).singleElement()
                .extracting(ResearchAgentCompletionReceipt.MergeReceipt::reasonCode)
                .isEqualTo("EVIDENCE_QUALIFICATION_REJECTED");
    }

    private Fixture fixture(boolean runStrictEvidenceFlag) {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        String runId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "dr301-" + runId);
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTaskId, workspaceId, runId);
        // json_object keeps this a real JSON object on both MySQL and H2's MySQL mode; a
        // bound VARCHAR would be stored as a JSON string scalar by H2 and break snapshot reads.
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode, agent_feature_flags_json)
                values (?, ?, ?, 'dr301 question', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1',
                    json_object('strict_research_evidence_validation', ?))
                """, runId, workspaceId, parentTaskId, runStrictEvidenceFlag);
        String rowId = Ids.newId();
        jdbcTemplate.update(
                "insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')",
                rowId, runId);
        String cellKey = "entity-1:field-0";
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, ?, 'field-0', 'old-0', 'CANDIDATE_READY', 0, 0, 1, 1)
                """, Ids.newId(), runId, rowId, cellKey);
        Map<String, Long> reserved = Map.of(
                "llm_calls", 2L, "search_calls", 2L, "fetch_calls", 2L, "read_calls", 2L,
                "extract_calls", 2L, "evidence_cards", 3L, "evidence_appended", 3L,
                "candidates_submitted", 2L, "candidate_merges_accepted", 1L,
                "candidate_merges_rejected", 1L);
        Map<String, Object> snapshotBudget = new LinkedHashMap<>();
        reserved.forEach(snapshotBudget::put);
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "dr301-task-" + runId, "dr301-idem-" + runId, 1, "DEEP_CELL", "entity-1", "main",
                1, 1, List.of(cellKey), snapshotBudget,
                List.of(new ResearchAgentTaskService.TargetCellBinding(cellKey, 0)),
                new ResearchAgentTaskService.TaskExecutionContext("research-fake",
                        Map.of("source_scope", List.of(Map.of(
                                "source_id", "source-0", "source_title", "Source 0",
                                "sample_text", "prefix trusted quote 0 suffix"))),
                        Map.of("query", "dr301 question")))).taskId();
        outboxService.enqueue(taskId);
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                runId, taskId, "dr301-budget-" + taskId, reserved));
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "worker-a", 300));
        return new Fixture(runId, taskId, List.of(cellKey), claim.leaseEpoch(), claim.fencingToken(),
                claim.snapshotDigest(), "deep-cell:" + taskId + ":" + claim.leaseEpoch() + ":" + claim.fencingToken());
    }

    private ResearchAgentCompletionEnvelope qualifiedEnvelope(Fixture fixture) {
        return envelope(fixture, "value-0");
    }

    private ResearchAgentCompletionEnvelope contradictedEnvelope(Fixture fixture) {
        return envelope(fixture, "value-1");
    }

    private ResearchAgentCompletionEnvelope envelope(Fixture fixture, String candidateValue) {
        String evidenceKey = "ev-dr301-" + fixture.taskId().substring(0, 8);
        ResearchAgentCompletionEnvelope.Evidence evidence = new ResearchAgentCompletionEnvelope.Evidence(
                evidenceKey, "window-0", "source-0", "Source 0", "dr301 question", "field-0",
                "trusted quote 0", candidateValue, "SUPPORTS", 900_000, 0, "WORKSPACE");
        ResearchAgentCompletionEnvelope.Candidate candidate = new ResearchAgentCompletionEnvelope.Candidate(
                "cand-dr301-" + fixture.taskId().substring(0, 8), fixture.cellKeys().get(0), 0, candidateValue,
                List.of(evidenceKey), 900_000);
        Map<String, Long> usage = Map.of(
                "llm_calls", 0L, "search_calls", 1L, "fetch_calls", 1L, "read_calls", 1L, "extract_calls", 1L,
                "evidence_cards", 1L, "candidates_submitted", 1L);
        ResearchAgentCompletionEnvelope unsigned = new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", fixture.taskId(), "worker-a", fixture.leaseEpoch(),
                fixture.fencingToken(), fixture.executionKey(), fixture.snapshotDigest(),
                "CANDIDATES_PROPOSED", usage,
                Map.of("search_hits", 1L, "documents", 1L, "windows", 1L),
                "sha256:" + "3".repeat(64), List.of(evidence), List.of(candidate), null);
        return unsigned.withEnvelopeDigest(canonicalizer.digest(unsigned));
    }

    private int count(String table, String column, String value) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where " + column + " = ?",
                Integer.class, value);
    }

    private record Fixture(
            String runId,
            String taskId,
            List<String> cellKeys,
            int leaseEpoch,
            long fencingToken,
            String snapshotDigest,
            String executionKey
    ) { }
}
