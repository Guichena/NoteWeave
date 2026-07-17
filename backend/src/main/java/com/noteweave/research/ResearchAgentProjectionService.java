package com.noteweave.research;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only projection over MA3 append-only execution tables. */
@Service
public class ResearchAgentProjectionService {

    private final JdbcTemplate jdbcTemplate;

    public ResearchAgentProjectionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> project(String researchRunId) {
        String mode = jdbcTemplate.query("select agent_execution_mode from research_run where id = ?",
                rs -> rs.next() ? rs.getString(1) : "", researchRunId);
        List<Map<String, Object>> tasks = jdbcTemplate.query("""
                select id, task_key, status, lease_epoch, fencing_token, worker_instance_id, attempt_count, lease_expires_at
                from research_agent_task where research_run_id = ? order by created_at, id
                """, (rs, rowNum) -> Map.<String, Object>of(
                "task_id", rs.getString("id"), "task_key", rs.getString("task_key"), "status", rs.getString("status"),
                "lease_epoch", rs.getInt("lease_epoch"), "fencing_token", rs.getLong("fencing_token"),
                "worker_instance_id", blank(rs.getString("worker_instance_id")), "attempt_count", rs.getInt("attempt_count"),
                "lease_expires_at", rs.getTimestamp("lease_expires_at") == null ? "" : rs.getTimestamp("lease_expires_at").toInstant().toString()
        ), researchRunId);
        List<Map<String, Object>> candidates = jdbcTemplate.query("""
                select id, task_id, cell_key, base_cell_version, confidence_score from research_agent_candidate
                where research_run_id = ? order by submitted_at, id
                """, (rs, rowNum) -> Map.<String, Object>of(
                "candidate_id", rs.getString("id"), "task_id", rs.getString("task_id"), "cell_key", rs.getString("cell_key"),
                "base_cell_version", rs.getInt("base_cell_version"), "confidence", rs.getBigDecimal("confidence_score").doubleValue()
        ), researchRunId);
        List<Map<String, Object>> merges = jdbcTemplate.query("""
                select merge_key, candidate_id, cell_key, decision, reason_code, expected_cell_version, result_cell_version
                from research_cell_merge where research_run_id = ? order by merged_at, id
                """, (rs, rowNum) -> Map.<String, Object>of(
                "merge_id", rs.getString("merge_key"), "candidate_id", rs.getString("candidate_id"), "cell_key", rs.getString("cell_key"),
                "decision", rs.getString("decision"), "reason_code", rs.getString("reason_code"),
                "expected_cell_version", rs.getInt("expected_cell_version"), "result_cell_version", rs.getInt("result_cell_version")
        ), researchRunId);
        List<Map<String, Object>> checkpoints = jdbcTemplate.query("""
                select checkpoint_seq, task_high_water_mark, candidate_high_water_mark, merge_high_water_mark
                from research_agent_checkpoint where research_run_id = ? order by checkpoint_seq
                """, (rs, rowNum) -> Map.<String, Object>of(
                "checkpoint_seq", rs.getInt(1), "task_high_water_mark", rs.getLong(2),
                "candidate_high_water_mark", rs.getLong(3), "merge_high_water_mark", rs.getLong(4)
        ), researchRunId);
        LinkedHashMap<String, Object> projection = new LinkedHashMap<>();
        projection.put("execution_mode", blank(mode));
        projection.put("tasks", tasks);
        projection.put("candidates", candidates);
        projection.put("merges", merges);
        projection.put("checkpoints", checkpoints);
        projection.put("task_count", tasks.size());
        projection.put("candidate_count", candidates.size());
        projection.put("merge_count", merges.size());
        return Map.copyOf(projection);
    }

    private String blank(String value) { return value == null ? "" : value; }
}
