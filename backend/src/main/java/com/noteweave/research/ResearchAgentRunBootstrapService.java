package com.noteweave.research;

import com.noteweave.common.Ids;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Creates the deterministic canonical matrix that distributed workers fill. */
@Service
public class ResearchAgentRunBootstrapService {
    private static final List<String> DEFAULT_COLUMNS = List.of(
            "answer", "key_evidence", "limitations", "implications");

    private final JdbcTemplate jdbcTemplate;

    public ResearchAgentRunBootstrapService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BootstrapReceipt bootstrap(String runId, String question, ResearchIntentResponse intent) {
        String branchId = Ids.newId();
        String rowId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_branch(
                    id, research_run_id, branch_key, branch_reason, branch_status,
                    hypothesis_summary, target_evidence_ids_json, created_round)
                values (?, ?, 'branch-main', 'INITIAL_RESEARCH_MATRIX', 'ACTIVE', ?, '[]', 1)
                """, branchId, runId, question);
        jdbcTemplate.update("""
                insert into research_row(
                    id, research_run_id, row_key, branch_id, search_query, read_focus,
                    row_status, verification_status)
                values (?, ?, 'subject', ?, ?, ?, 'DISCOVERED', 'PENDING')
                """, rowId, runId, branchId, question, readFocus(intent, question));

        for (String column : DEFAULT_COLUMNS) {
            jdbcTemplate.update("""
                    insert into research_cell(
                        id, research_run_id, research_row_id, cell_key, branch_id, column_key,
                        candidate_value, cell_status, evidence_refs_json, repair_count,
                        cell_version, plan_revision, entity_set_version, high_risk)
                    values (?, ?, ?, ?, ?, ?, null, 'GAP', '[]', 0, 0, 1, 1, ?)
                    """, Ids.newId(), runId, rowId, "subject:" + column, branchId, column, false);
        }
        return new BootstrapReceipt(branchId, rowId, DEFAULT_COLUMNS.size());
    }

    private String readFocus(ResearchIntentResponse intent, String question) {
        if (intent != null && intent.researchGoal() != null && !intent.researchGoal().isBlank()) {
            return intent.researchGoal().trim();
        }
        return question;
    }

    public record BootstrapReceipt(String branchId, String rowId, int cellCount) { }
}
