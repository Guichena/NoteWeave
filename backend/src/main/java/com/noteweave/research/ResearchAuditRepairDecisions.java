package com.noteweave.research;

import org.springframework.jdbc.core.JdbcTemplate;

/** 补查识别与审计决策结算共用同一组类型，不把缺少证据误当成结论冲突。 */
final class ResearchAuditRepairDecisions {
    static final String TYPES = "('QUORUM_REPAIR_REQUIRED','EVIDENCE_AUDIT_REPAIR_REQUIRED','EVIDENCE_AUDIT_BLOCKED')";
    private static final String AUDIT_TYPES = "('EVIDENCE_AUDIT_REPAIR_REQUIRED','EVIDENCE_AUDIT_BLOCKED')";

    static void settle(JdbcTemplate jdbc, String runId, int maximum) {
        jdbc.update("""
                update research_verifier_decision set decision_status = 'RESOLVED'
                where research_run_id = ? and decision_type in %s and decision_status = 'OPEN'
                  and exists (select 1 from research_cell cell where cell.research_run_id = ?
                    and cell.cell_key = research_verifier_decision.target_id and cell.semantic_audit_status = 'PASS')
                """.formatted(AUDIT_TYPES), runId, runId);
        jdbc.update("""
                update research_verifier_decision set decision_status = 'EXHAUSTED'
                where research_run_id = ? and decision_type in %s and decision_status = 'OPEN'
                  and exists (select 1 from research_cell cell where cell.research_run_id = ?
                    and cell.cell_key = research_verifier_decision.target_id and cell.repair_count >= ?
                    and cell.active_task_id is null and cell.semantic_audit_status not in ('PASS','PENDING'))
                  and not exists (select 1 from research_agent_task task where task.research_run_id = ?
                    and task.status not in ('SUBMITTED','FAILED','CANCELLED')
                    and task.target_cells_json like concat('%%"',research_verifier_decision.target_id,'"%%'))
                """.formatted(AUDIT_TYPES), runId, runId, maximum, runId);
    }

    private ResearchAuditRepairDecisions() { }
}
