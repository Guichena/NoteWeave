package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DR-203 contract tests. They run against the real {@code classpath:db/migration} Flyway history on
 * H2 in {@code MODE=MySQL}, so the V106 migration apply is part of the evidence.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentReplanAuditServiceTest {

    private static final String RUN_ID = "research_run_id";
    private static final String TABLE = "research_agent_replan_audit";
    private static final String OLD_DIGEST = "sha256:" + "a".repeat(64);
    private static final String NEW_DIGEST = "sha256:" + "b".repeat(64);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentReplanAuditService service;

    @Test
    void migrationShouldApplyOnH2AndExposeOnlyBoundedDecisionColumns() {
        List<ColumnMeta> columns = jdbcTemplate.query("""
                select column_name, data_type, character_maximum_length
                from information_schema.columns
                where lower(table_name) = ?
                """, (rs, rowNum) -> {
            long maxLength = rs.getLong(3);
            boolean nullLength = rs.wasNull();
            return new ColumnMeta(rs.getString(1), rs.getString(2), nullLength ? null : (int) maxLength);
        }, TABLE);

        assertThat(columns).hasSize(13);
        List<String> names = columns.stream().map(column -> column.name().toLowerCase()).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "id", "research_run_id", "plan_revision_from", "plan_revision_to",
                "old_plan_digest", "new_plan_digest", "deviation_type", "observed_facts",
                "affected_cells_json", "budget_delta_json", "evidence_refs_json",
                "selected_repair", "created_at");
        // No free-form reasoning slot may exist in the audit table.
        assertThat(names).noneMatch(name -> name.matches(".*(reasoning|thought|chain|prompt|raw|transcript).*"));

        Map<String, ColumnMeta> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(
                        column -> column.name().toLowerCase(java.util.Locale.ROOT),
                        column -> column,
                        (left, right) -> left));
        // Every decision-text column is a bounded varchar, so the table cannot become a
        // Chain-of-Thought dump; only the three structured payload columns are LOB-shaped and
        // their serialized size is capped by the service before insert.
        for (String narrow : List.of("id", "research_run_id", "old_plan_digest", "new_plan_digest",
                "deviation_type", "observed_facts", "selected_repair")) {
            assertThat(byName.get(narrow).dataType())
                    .as("decision text column %s must be a character column", narrow)
                    .containsIgnoringCase("char");
            assertThat(byName.get(narrow).maxLength())
                    .as("decision text column %s must stay bounded", narrow)
                    .isNotNull()
                    .isBetween(1, 2048);
        }
        for (String payload : List.of("affected_cells_json", "budget_delta_json", "evidence_refs_json")) {
            ColumnMeta column = byName.get(payload);
            assertThat(column).as("payload column %s must exist", payload).isNotNull();
            assertThat(column.maxLength() == null || column.maxLength() > 2048)
                    .as("payload column %s must not be a narrow varchar", payload)
                    .isTrue();
        }
    }

    @Test
    void shouldPersistAndReadBackAReplanDecisionSummary() {
        String runId = seedRun();
        ResearchAgentReplanAuditService.ReplanAudit persisted = service.recordReplan(
                new ResearchAgentReplanAuditService.ReplanAuditCommand(
                        runId, 0, 1, OLD_DIGEST, NEW_DIGEST,
                        ResearchReplanDeviationTypes.EVIDENCE_CONFLICT,
                        "Two qualified sources contradict on the calibration window.",
                        List.of("entity-1:answer", "entity-1:key_evidence"),
                        Map.of("llm_calls", 3L, "search_calls", -1L, "evidence_cards", 5L),
                        List.of("evidence:" + "c".repeat(64), "receipt:" + "d".repeat(64)),
                        "COUNTERFACTUAL"));

        assertThat(persisted.id()).isNotBlank();
        assertThat(persisted.researchRunId()).isEqualTo(runId);
        assertThat(persisted.planRevisionFrom()).isZero();
        assertThat(persisted.planRevisionTo()).isEqualTo(1);

        List<ResearchAgentReplanAuditService.ReplanAudit> byRun = service.findByRun(runId);
        assertThat(byRun).hasSize(1);
        ResearchAgentReplanAuditService.ReplanAudit read = byRun.get(0);
        assertThat(read.oldPlanDigest()).isEqualTo(OLD_DIGEST);
        assertThat(read.newPlanDigest()).isEqualTo(NEW_DIGEST);
        assertThat(read.deviationType()).isEqualTo("EVIDENCE_CONFLICT");
        assertThat(read.observedFacts()).doesNotContain("\n");
        assertThat(read.affectedCells()).containsExactly("entity-1:answer", "entity-1:key_evidence");
        assertThat(read.budgetDelta())
                .containsExactlyInAnyOrderEntriesOf(Map.of("llm_calls", 3L, "search_calls", -1L, "evidence_cards", 5L));
        assertThat(read.evidenceRefs()).hasSize(2);
        assertThat(read.selectedRepair()).isEqualTo("COUNTERFACTUAL");
        assertThat(read.createdAt()).isNotNull();
        assertThat(service.findByRunAndRevision(runId, 1)).isNotNull();
        assertThat(service.findByRunAndRevision(runId, 2)).isNull();
    }

    @Test
    void shouldRejectADuplicatePlanRevisionWithoutWritingASecondRow() {
        String runId = seedRun();
        service.recordReplan(command(runId, 0, 1, ResearchReplanDeviationTypes.CONTEXT_CHANGED));

        assertThatThrownBy(() ->
                service.recordReplan(command(runId, 0, 1, ResearchReplanDeviationTypes.CONTEXT_CHANGED)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("RESEARCH_REPLAN_AUDIT_REVISION_CONFLICT"));

        assertThat(countFor(runId)).isEqualTo(1);
    }

    @Test
    void shouldAcceptEveryAuditableDeviationTypeAndRejectUnknownOnes() {
        String runId = seedRun();
        List<String> vocabulary = ResearchReplanDeviationTypes.VALUES.stream().sorted().toList();
        assertThat(vocabulary).containsExactly(
                "CONSTRAINT_CONFLICT", "CONTEXT_CHANGED", "EVIDENCE_CONFLICT",
                "INPUT_MISUNDERSTOOD", "MARGINAL_GAIN_EXHAUSTED", "PROVIDER_FAILURE",
                "SCOPE_EXPANDED");

        int revision = 1;
        for (String deviationType : vocabulary) {
            service.recordReplan(command(runId, revision - 1, revision, deviationType));
            revision++;
        }
        assertThat(countFor(runId)).isEqualTo(7);

        assertThatThrownBy(() -> service.recordReplan(
                command(runId, 7, 8, "MODEL_PREFERENCE")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_DEVIATION_TYPE_INVALID"));
        assertThat(countFor(runId)).isEqualTo(7);
    }

    @Test
    void shouldRejectAReplanWhosePlanDigestDoesNotChange() {
        String runId = seedRun();
        assertThatThrownBy(() -> service.recordReplan(
                new ResearchAgentReplanAuditService.ReplanAuditCommand(
                        runId, 0, 1, OLD_DIGEST, OLD_DIGEST,
                        ResearchReplanDeviationTypes.MARGINAL_GAIN_EXHAUSTED,
                        "No new information is expected from the current path.",
                        List.of("entity-1:answer"), Map.of("llm_calls", 0L), null, "FREEZE_UNRESOLVED")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("RESEARCH_REPLAN_DIGEST_UNCHANGED"));
        assertThat(countFor(runId)).isZero();
    }

    @Test
    void shouldRejectAReplanWithoutAffectedCells() {
        String runId = seedRun();
        assertThatThrownBy(() -> service.recordReplan(
                new ResearchAgentReplanAuditService.ReplanAuditCommand(
                        runId, 0, 1, OLD_DIGEST, NEW_DIGEST,
                        ResearchReplanDeviationTypes.INPUT_MISUNDERSTOOD,
                        "The compiled goal omitted a required dimension.",
                        List.of(), Map.of("llm_calls", 1L), null, "REPLAN_TARGETED_SEARCH")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_AFFECTED_CELLS_INVALID"));
        assertThat(countFor(runId)).isZero();
    }

    @Test
    void shouldRejectUnboundedOrOffVocabularyDecisionPayloads() {
        String runId = seedRun();

        assertThatThrownBy(() -> service.recordReplan(
                new ResearchAgentReplanAuditService.ReplanAuditCommand(
                        runId, 0, 1, OLD_DIGEST, NEW_DIGEST,
                        ResearchReplanDeviationTypes.PROVIDER_FAILURE,
                        "x".repeat(10_000), List.of("entity-1:answer"),
                        Map.of("llm_calls", 1L), null, "READ_MORE")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_OBSERVED_FACTS_INVALID"));

        assertThatThrownBy(() -> service.recordReplan(
                new ResearchAgentReplanAuditService.ReplanAuditCommand(
                        runId, 0, 1, OLD_DIGEST, NEW_DIGEST,
                        ResearchReplanDeviationTypes.PROVIDER_FAILURE,
                        "Search provider returned 429 for the whole wave.",
                        List.of("entity-1:answer"), Map.of("llm_calls", 1L), null, "y".repeat(10_000))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_SELECTED_REPAIR_INVALID"));

        assertThatThrownBy(() -> service.recordReplan(
                command(runId, 0, 1, ResearchReplanDeviationTypes.CONSTRAINT_CONFLICT,
                        List.of("z".repeat(500)))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_AFFECTED_CELLS_INVALID"));

        assertThatThrownBy(() -> service.recordReplan(
                new ResearchAgentReplanAuditService.ReplanAuditCommand(
                        runId, 0, 1, OLD_DIGEST, NEW_DIGEST,
                        ResearchReplanDeviationTypes.CONSTRAINT_CONFLICT,
                        "Plan constraints cannot be satisfied together.",
                        List.of("entity-1:answer"), Map.of("reasoning_chain", 1L), null, "GLOBAL_RECOMPILE")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_BUDGET_DELTA_INVALID"));

        assertThatThrownBy(() -> service.recordReplan(
                new ResearchAgentReplanAuditService.ReplanAuditCommand(
                        runId, 0, 1, "not-a-digest", NEW_DIGEST,
                        ResearchReplanDeviationTypes.CONTEXT_CHANGED,
                        "Source scope changed after planning.",
                        List.of("entity-1:answer"), Map.of("llm_calls", 0L), null, "LOCAL_STAGE_ROLLBACK")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_DIGEST_INVALID"));

        // Even a legal cell count is rejected when the serialized payload would exceed the cap.
        List<String> oversizedCells = java.util.stream.IntStream.range(0, 80)
                .mapToObj(index -> "cell-%02d-%s".formatted(index, "q".repeat(50)))
                .toList();
        assertThatThrownBy(() -> service.recordReplan(
                command(runId, 0, 1, ResearchReplanDeviationTypes.CONSTRAINT_CONFLICT, oversizedCells)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_REPLAN_AFFECTED_CELLS_INVALID"));

        assertThat(countFor(runId)).isZero();
    }

    @Test
    void databaseBoundaryShouldEnforceDigestInequalityAndRevisionUniqueness() {
        String runId = seedRun();
        insertRaw(runId, 1, OLD_DIGEST, NEW_DIGEST);

        // Idempotency key: the same (run, resulting revision) cannot be inserted twice.
        assertThatThrownBy(() -> insertRaw(runId, 1, OLD_DIGEST, NEW_DIGEST))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countFor(runId)).isEqualTo(1);

        // A Replan without a changed plan digest is rejected even below the service layer.
        assertThatThrownBy(() -> insertRaw(runId, 2, OLD_DIGEST, OLD_DIGEST))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countFor(runId)).isEqualTo(1);
    }

    private ResearchAgentReplanAuditService.ReplanAuditCommand command(
            String runId, int from, int to, String deviationType) {
        return command(runId, from, to, deviationType, List.of("entity-1:answer"));
    }

    private ResearchAgentReplanAuditService.ReplanAuditCommand command(
            String runId, int from, int to, String deviationType, List<String> affectedCells) {
        return new ResearchAgentReplanAuditService.ReplanAuditCommand(
                runId, from, to, OLD_DIGEST, NEW_DIGEST, deviationType,
                "One observable deviation on the current plan.",
                affectedCells, Map.of("llm_calls", 1L), null, "REPLAN_TARGETED_SEARCH");
    }

    private void insertRaw(String runId, int revisionTo, String oldDigest, String newDigest) {
        jdbcTemplate.update("""
                insert into research_agent_replan_audit(
                    id, research_run_id, plan_revision_from, plan_revision_to, old_plan_digest,
                    new_plan_digest, deviation_type, observed_facts, affected_cells_json,
                    budget_delta_json, evidence_refs_json, selected_repair)
                values (?, ?, ?, ?, ?, ?, 'PROVIDER_FAILURE', 'bounded facts', '["entity-1:answer"]',
                        '{"llm_calls":1}', null, 'READ_MORE')
                """, Ids.newId(), runId, revisionTo - 1, revisionTo, oldDigest, newDigest);
    }

    private int countFor(String runId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from research_agent_replan_audit where " + RUN_ID + " = ?",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    private String seedRun() {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        String runId = Ids.newId();
        jdbcTemplate.update(
                "insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "replan-audit-" + runId);
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTaskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json, status)
                values (?, ?, ?, 'replan audit', 'DEFAULT', '[]', 'RUNNING')
                """, runId, workspaceId, parentTaskId);
        return runId;
    }

    private record ColumnMeta(String name, String dataType, Integer maxLength) { }
}
