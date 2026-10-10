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
        return findAllByRunIds(List.of(researchRunId)).getOrDefault(researchRunId, List.of());
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
        addHydrationSnapshots(researchRunIds, recordsByRunId);
        recordsByRunId.replaceAll((ignored, records) -> List.copyOf(records));
        return Map.copyOf(recordsByRunId);
    }

    /**
     * INCREMENTAL_V1 Runs never write research_execution_checkpoint; their resumable checkpoints are
     * the canonical-ledger hydration snapshots, addressed by checkpoint_seq on the resume endpoint.
     * Only Runs whose frozen feature flags allow hydration list them, so every listed checkpoint can
     * actually be resumed.
     */
    private void addHydrationSnapshots(List<String> researchRunIds,
                                       Map<String, List<ResearchCheckpointRecord>> recordsByRunId) {
        List<String> missing = researchRunIds.stream().filter(id -> !recordsByRunId.containsKey(id)).toList();
        if (missing.isEmpty()) return;
        String placeholders = String.join(", ", java.util.Collections.nCopies(missing.size(), "?"));
        try {
            queryHydrationSnapshots(placeholders, missing, recordsByRunId);
        } catch (org.springframework.jdbc.BadSqlGrammarException schemaWithoutSnapshots) {
            // 只建了旧检查点表的精简测试库；没有快照可列
        }
    }

    private void queryHydrationSnapshots(String placeholders, List<String> missing,
                                         Map<String, List<ResearchCheckpointRecord>> recordsByRunId) {
        jdbcTemplate.query("""
                select snapshot.research_run_id, snapshot.checkpoint_seq, snapshot.payload_sha256,
                       snapshot.content_size, snapshot.created_at, run.agent_feature_flags_json
                from research_checkpoint_hydration_snapshot snapshot
                join research_run run on run.id = snapshot.research_run_id
                where snapshot.research_run_id in (%s)
                order by snapshot.research_run_id, snapshot.checkpoint_seq asc
                """.formatted(placeholders), rs -> {
            String flags = rs.getString("agent_feature_flags_json");
            if (flags == null || !flags.replace(" ", "").contains("\"" + ResearchAgentFeatureFlagService.CHECKPOINT_HYDRATION + "\":true")) {
                return;
            }
            recordsByRunId.computeIfAbsent(rs.getString("research_run_id"), ignored -> new ArrayList<>())
                    .add(new ResearchCheckpointRecord(
                            rs.getInt("checkpoint_seq"), HYDRATION_SNAPSHOT_TYPE, "",
                            rs.getString("payload_sha256"), rs.getLong("content_size"),
                            "", "", "{}", rs.getTimestamp("created_at").toInstant()));
        }, missing.toArray());
    }

    static final String HYDRATION_SNAPSHOT_TYPE = "LEDGER_HYDRATION";

    ResearchCheckpointRecord get(String workspaceId, String researchRunId, int checkpointNo) {
        return jdbcTemplate.query("""
                select rec.checkpoint_no, rec.snapshot_type, rec.object_key, rec.payload_sha256,
                       rec.content_size, rec.active_branch_key, rec.final_loop_decision,
                       rec.summary_json, rec.created_at
                from research_execution_checkpoint rec
                join research_run rr on rr.id = rec.research_run_id
                where rr.workspace_id = ? and rr.id = ? and rec.checkpoint_no = ?
                """, rs -> {
            if (rs.next()) return mapRecord(rs);
            ResearchCheckpointRecord snapshot = findHydrationSnapshot(workspaceId, researchRunId, checkpointNo);
            if (snapshot == null) {
                throw new BusinessException(
                        "RESEARCH_CHECKPOINT_NOT_FOUND",
                        "Research checkpoint does not exist"
                );
            }
            return snapshot;
        }, workspaceId, researchRunId, checkpointNo);
    }

    private ResearchCheckpointRecord findHydrationSnapshot(String workspaceId, String researchRunId, int checkpointSeq) {
        try {
            return jdbcTemplate.query("""
                    select snapshot.checkpoint_seq, snapshot.payload_sha256, snapshot.content_size, snapshot.created_at
                    from research_checkpoint_hydration_snapshot snapshot
                    join research_run run on run.id = snapshot.research_run_id
                    where run.workspace_id = ? and run.id = ? and snapshot.checkpoint_seq = ?
                    """, rs -> rs.next() ? new ResearchCheckpointRecord(
                    rs.getInt("checkpoint_seq"), HYDRATION_SNAPSHOT_TYPE, "",
                    rs.getString("payload_sha256"), rs.getLong("content_size"),
                    "", "", "{}", rs.getTimestamp("created_at").toInstant()) : null,
                    workspaceId, researchRunId, checkpointSeq);
        } catch (org.springframework.jdbc.BadSqlGrammarException schemaWithoutSnapshots) {
            return null;
        }
    }

    /** 账本快照的载荷直接存在库里，不经过对象存储。 */
    String hydrationSnapshotPayload(String researchRunId, int checkpointSeq) {
        List<String> payloads = jdbcTemplate.queryForList("""
                select payload_json from research_checkpoint_hydration_snapshot
                where research_run_id = ? and checkpoint_seq = ?
                """, String.class, researchRunId, checkpointSeq);
        return payloads.isEmpty() ? "{}" : payloads.get(0);
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
