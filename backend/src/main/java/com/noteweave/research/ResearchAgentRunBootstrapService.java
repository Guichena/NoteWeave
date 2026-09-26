package com.noteweave.research;

import com.noteweave.common.Ids;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Creates the deterministic canonical matrix that distributed workers fill. */
@Service
public class ResearchAgentRunBootstrapService {
    private static final List<String> DEFAULT_COLUMNS = List.of(
            "answer", "key_evidence", "limitations", "implications");

    private final JdbcTemplate jdbcTemplate;
    private final ResearchMatrixPlanningService matrixPlanningService;

    public ResearchAgentRunBootstrapService(
            JdbcTemplate jdbcTemplate,
            ResearchMatrixPlanningService matrixPlanningService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.matrixPlanningService = matrixPlanningService;
    }

    public BootstrapReceipt bootstrap(String runId, String question, ResearchIntentResponse intent) {
        String branchId = Ids.newId();
        ResearchMatrixPlanningService.MatrixPlan shadowPlan =
                matrixPlanningService.planAndRecord(runId, question, intent);
        List<ResearchMatrixPlanningService.RowPlan> rows = matrixPlanningService.enabledForRun(runId)
                ? shadowPlan.rows()
                : List.of(new ResearchMatrixPlanningService.RowPlan("subject", "Subject"));
        List<ResearchMatrixPlanningService.ColumnPlan> columns = matrixPlanningService.enabledForRun(runId)
                ? shadowPlan.columns()
                : DEFAULT_COLUMNS.stream().map(column -> new ResearchMatrixPlanningService.ColumnPlan(
                        column, column, false, "QUALIFIED_SOURCE")).toList();
        jdbcTemplate.update("""
                insert into research_branch(
                    id, research_run_id, branch_key, branch_reason, branch_status,
                    hypothesis_summary, target_evidence_ids_json, created_round)
                values (?, ?, 'branch-main', 'INITIAL_RESEARCH_MATRIX', 'ACTIVE', ?, '[]', 1)
                """, branchId, runId,
                shadowPlan.bounded() ? question + " [PLAN_BOUNDED]" : question);
        String firstRowId = null;
        int cellCount = 0;
        boolean conflictSensitive = conflictSensitive(question, intent);
        for (ResearchMatrixPlanningService.RowPlan row : rows) {
            String rowId = Ids.newId();
            if (firstRowId == null) firstRowId = rowId;
            jdbcTemplate.update("""
                    insert into research_row(
                        id, research_run_id, row_key, branch_id, source_title, search_query, read_focus,
                        row_status, verification_status)
                    values (?, ?, ?, ?, ?, ?, ?, 'DISCOVERED', 'PENDING')
                    """, rowId, runId, row.key(), branchId, row.label(), question, readFocus(intent, question));
            for (ResearchMatrixPlanningService.ColumnPlan column : columns) {
                jdbcTemplate.update("""
                        insert into research_cell(
                            id, research_run_id, research_row_id, cell_key, branch_id, column_key,
                            candidate_value, cell_status, evidence_refs_json, repair_count,
                            cell_version, plan_revision, entity_set_version, high_risk)
                        values (?, ?, ?, ?, ?, ?, null, 'GAP', '[]', 0, 0, 1, 1, ?)
                        """, Ids.newId(), runId, rowId, row.key() + ":" + column.key(), branchId,
                        column.key(), column.highRisk()
                                || (conflictSensitive && List.of("answer", "key_evidence").contains(column.key())));
                cellCount++;
            }
        }
        return new BootstrapReceipt(branchId, firstRowId, cellCount);
    }

    private boolean conflictSensitive(String question, ResearchIntentResponse intent) {
        StringBuilder text = new StringBuilder(question == null ? "" : question);
        if (intent != null) {
            text.append(' ').append(intent.researchGoal() == null ? "" : intent.researchGoal());
        }
        String normalized = text.toString().toLowerCase(Locale.ROOT);
        return List.of("冲突", "反证", "相反", "方向相反", "conflict", "counterevidence",
                        "counterfactual", "opposing", "contradict")
                .stream().anyMatch(normalized::contains);
    }

    private String readFocus(ResearchIntentResponse intent, String question) {
        if (intent != null && intent.researchGoal() != null && !intent.researchGoal().isBlank()) {
            return intent.researchGoal().trim();
        }
        return question;
    }

    public record BootstrapReceipt(String branchId, String rowId, int cellCount) { }
}
