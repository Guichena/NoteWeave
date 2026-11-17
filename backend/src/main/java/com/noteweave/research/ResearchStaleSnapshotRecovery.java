package com.noteweave.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 在服务端重新核对冻结范围，不能只凭 Worker 上报的错误结束当前任务。 */
final class ResearchStaleSnapshotRecovery {
    static boolean invalidCandidateScope(JdbcTemplate jdbc, String run, String task) {
        jdbc.queryForList("select id from research_run where id = ? for update", String.class, run);
        List<Scope> tasks = jdbc.query("""
                select role,status,plan_revision,entity_set_version,target_bindings_json from research_agent_task
                where id = ? and research_run_id = ? for update
                """, (rs,n) -> new Scope(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5)),task,run);
        if (tasks.isEmpty()) return false;
        Scope frozen = tasks.get(0);
        if (!Set.of("DEEP_CELL","COUNTERFACTUAL").contains(frozen.role())
                || Set.of("SUBMITTED","FAILED","CANCELLED").contains(frozen.status()) || frozen.bindings() == null) return false;
        try {
            var bindings = new ObjectMapper().readTree(frozen.bindings());
            if (bindings.isTextual()) bindings = new ObjectMapper().readTree(bindings.asText());
            boolean invalid = false;
            for (var binding : bindings) {
                var cells = jdbc.query("""
                        select cell_version,plan_revision,entity_set_version from research_cell
                        where research_run_id = ? and cell_key = ? for update
                        """, (rs,n) -> new int[]{rs.getInt(1),rs.getInt(2),rs.getInt(3)},run,binding.path("cell_id").asText());
                invalid |= cells.isEmpty() || cells.get(0)[0] != binding.path("expected_version").asInt(-1)
                        || cells.get(0)[1] != frozen.plan() || cells.get(0)[2] != frozen.entities();
            }
            return invalid;
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return false;
        }
    }

    private record Scope(String role,String status,int plan,int entities,String bindings) { }
    private ResearchStaleSnapshotRecovery() { }
}
