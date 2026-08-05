package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns checkpoint persistence queries, including workspace-scoped lookup. */
@Repository
public class ResearchCheckpointStore {

    private final JdbcTemplate jdbcTemplate;

    public ResearchCheckpointStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    List<ResearchCheckpointRecord> findAll(String researchRunId) {
        return jdbcTemplate.query("""
                select checkpoint_no, snapshot_type, object_key, payload_sha256, content_size,
                       active_branch_key, final_loop_decision, summary_json, created_at
                from research_execution_checkpoint
                where research_run_id = ?
                order by checkpoint_no asc, created_at asc, id asc
                """, (rs, rowNum) -> mapRecord(rs), researchRunId);
    }

    Map<String, List<ResearchCheckpointRecord>> findAllByRunIds(List<String> researchRunIds) {
        if (researchRunIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(researchRunIds.size(), "?"));
        LinkedHashMap<String, List<ResearchCheckpointRecord>> recordsByRunId = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select research_run_id, checkpoint_no, snapshot_type, object_key, payload_sha256,
                       content_size, active_branch_key, final_loop_decision, summary_json, created_at
                from research_execution_checkpoint
                where research_run_id in (%s)
                order by research_run_id, checkpoint_no asc, created_at asc, id asc
                """.formatted(placeholders), rs -> {
            recordsByRunId.computeIfAbsent(rs.getString("research_run_id"), ignored -> new ArrayList<>())
                    .add(mapRecord(rs));
        }, researchRunIds.toArray());
        recordsByRunId.replaceAll((ignored, records) -> List.copyOf(records));
        return Map.copyOf(recordsByRunId);
    }

    ResearchCheckpointRecord get(String workspaceId, String researchRunId, int checkpointNo) {
        return jdbcTemplate.query("""
                select rec.checkpoint_no, rec.snapshot_type, rec.object_key, rec.payload_sha256,
                       rec.content_size, rec.active_branch_key, rec.final_loop_decision,
                       rec.summary_json, rec.created_at
                from research_execution_checkpoint rec
                join research_run rr on rr.id = rec.research_run_id
                where rr.workspace_id = ? and rr.id = ? and rec.checkpoint_no = ?
                """, rs -> {
            if (!rs.next()) {
                throw new BusinessException(
                        "RESEARCH_CHECKPOINT_NOT_FOUND",
                        "Research checkpoint does not exist"
                );
            }
            return mapRecord(rs);
        }, workspaceId, researchRunId, checkpointNo);
    }

    private ResearchCheckpointRecord mapRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResearchCheckpointRecord(
                rs.getInt("checkpoint_no"),
                rs.getString("snapshot_type"),
                rs.getString("object_key"),
                rs.getString("payload_sha256"),
                rs.getLong("content_size"),
                rs.getString("active_branch_key"),
                rs.getString("final_loop_decision"),
                rs.getString("summary_json"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
