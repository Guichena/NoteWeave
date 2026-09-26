package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Compiles immutable canonical-ledger hydration payloads at checkpoint time. */
@Service
class ResearchAgentCheckpointSnapshotCompiler {
    static final String SCHEMA_VERSION = "research-agent-checkpoint-hydration.v1";
    private static final String DIGEST_DOMAIN = "research-agent-checkpoint-hydration.v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;

    ResearchAgentCheckpointSnapshotCompiler(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentCompletionCanonicalizer canonicalizer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
    }

    CompiledSnapshot compile(
            String checkpointId,
            int checkpointSeq,
            ResearchBudgetAndCheckpointService.CheckpointCommand command
    ) {
        String runId = command.researchRunId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", SCHEMA_VERSION);
        payload.put("source_research_run_id", runId);
        payload.put("source_checkpoint_id", checkpointId);
        payload.put("checkpoint_seq", checkpointSeq);
        payload.put("wave_no", command.waveNo());
        payload.put("round_no", command.roundNo());
        payload.put("plan_revision", command.planRevision());
        payload.put("entity_set_version", command.entitySetVersion());
        payload.put("ledger_digest", command.ledgerHash());
        payload.put("matrix_plan", latestJson("""
                select plan_json from research_matrix_plan
                where research_run_id = ? order by created_at desc, id desc limit 1
                """, runId));
        payload.put("branches", branches(runId));
        payload.put("rows", rows(runId));
        payload.put("cells", cells(runId));
        payload.put("accepted_evidence", acceptedEvidence(runId));
        payload.put("open_decisions", openDecisions(runId));
        payload.put("stages", stages(runId));
        payload.put("budget_summary", command.budgetSummary());
        payload.put("high_water_marks", Map.of(
                "task", command.taskHighWaterMark(),
                "candidate", command.candidateHighWaterMark(),
                "merge", command.mergeHighWaterMark()));

        String canonicalJson = canonicalizer.canonicalJsonValue(payload);
        byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
        String payloadSha256 = sha256(bytes);
        String canonicalDigest = canonicalizer.domainSeparatedDigest(DIGEST_DOMAIN, payload);
        jdbcTemplate.update("""
                insert into research_checkpoint_hydration_snapshot(
                    id, research_run_id, checkpoint_id, checkpoint_seq, schema_version,
                    payload_json, content_size, payload_sha256, canonical_digest, ledger_digest)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), runId, checkpointId, checkpointSeq, SCHEMA_VERSION,
                canonicalJson, bytes.length, payloadSha256, canonicalDigest, command.ledgerHash());
        return new CompiledSnapshot(canonicalJson, bytes.length, payloadSha256, canonicalDigest);
    }

    private List<Map<String, Object>> branches(String runId) {
        return jdbcTemplate.query("""
                select branch.branch_key, parent.branch_key as parent_branch_key,
                       branch.branch_reason, branch.branch_status, branch.hypothesis_summary,
                       branch.target_evidence_ids_json, branch.created_round
                from research_branch branch
                left join research_branch parent on parent.id = branch.parent_branch_id
                where branch.research_run_id = ? order by branch.branch_key
                """, (rs, rowNum) -> map(
                "branch_key", rs.getString("branch_key"),
                "parent_branch_key", rs.getString("parent_branch_key"),
                "branch_reason", rs.getString("branch_reason"),
                "branch_status", rs.getString("branch_status"),
                "hypothesis_summary", rs.getString("hypothesis_summary"),
                "target_evidence_ids", readJson(rs.getString("target_evidence_ids_json"), List.of()),
                "created_round", rs.getInt("created_round")), runId);
    }

    private List<Map<String, Object>> rows(String runId) {
        return jdbcTemplate.query("""
                select rr.row_key, rb.branch_key, rr.source_id, rr.source_title,
                       rr.search_query, rr.read_focus, rr.evidence_id, rr.row_status,
                       rr.relation_type, rr.support_score, rr.conflict_score, rr.support_level,
                       rr.verification_status, rr.verifier_note, rr.repair_hint
                from research_row rr left join research_branch rb on rb.id = rr.branch_id
                where rr.research_run_id = ? order by rr.row_key
                """, (rs, rowNum) -> map(
                "row_key", rs.getString("row_key"), "branch_key", rs.getString("branch_key"),
                "source_id", rs.getString("source_id"), "source_title", rs.getString("source_title"),
                "search_query", rs.getString("search_query"), "read_focus", rs.getString("read_focus"),
                "evidence_id", rs.getString("evidence_id"), "row_status", rs.getString("row_status"),
                "relation_type", rs.getString("relation_type"), "support_score", rs.getBigDecimal("support_score"),
                "conflict_score", rs.getBigDecimal("conflict_score"), "support_level", rs.getString("support_level"),
                "verification_status", rs.getString("verification_status"),
                "verifier_note", rs.getString("verifier_note"), "repair_hint", rs.getString("repair_hint")), runId);
    }

    private List<Map<String, Object>> cells(String runId) {
        return jdbcTemplate.query("""
                select rc.cell_key, rr.row_key, rb.branch_key, rc.column_key,
                       rc.candidate_value, rc.cell_status, rc.confidence_score,
                       rc.confidence_score_ppm, rc.evidence_refs_json,
                       rc.last_verifier_decision, rc.repair_count, rc.cell_version,
                       rc.plan_revision, rc.entity_set_version, rc.last_merge_id, rc.high_risk
                from research_cell rc
                join research_row rr on rr.id = rc.research_row_id
                left join research_branch rb on rb.id = rc.branch_id
                where rc.research_run_id = ? order by rc.cell_key
                """, (rs, rowNum) -> map(
                "cell_key", rs.getString("cell_key"), "row_key", rs.getString("row_key"),
                "branch_key", rs.getString("branch_key"), "column_key", rs.getString("column_key"),
                "candidate_value", rs.getString("candidate_value"), "cell_status", rs.getString("cell_status"),
                "confidence_score", rs.getBigDecimal("confidence_score"),
                "confidence_score_ppm", rs.getObject("confidence_score_ppm"),
                "evidence_refs", readJson(rs.getString("evidence_refs_json"), List.of()),
                "last_verifier_decision", rs.getString("last_verifier_decision"),
                "repair_count", rs.getInt("repair_count"), "cell_version", rs.getInt("cell_version"),
                "plan_revision", rs.getInt("plan_revision"), "entity_set_version", rs.getInt("entity_set_version"),
                "last_merge_id", rs.getString("last_merge_id"), "high_risk", rs.getBoolean("high_risk")), runId);
    }

    private List<Map<String, Object>> acceptedEvidence(String runId) {
        return jdbcTemplate.query("""
                select distinct evidence.evidence_key, evidence.window_id, evidence.source_id,
                       evidence.source_title, evidence.source_url, evidence.provider, evidence.adapter,
                       evidence.search_query, evidence.read_focus, evidence.quote_text, evidence.claim_text,
                       evidence.relation_type, evidence.support_score, evidence.conflict_score,
                       evidence.snapshot_status, evidence.snapshot_key, evidence.source_origin,
                       evidence.source_domain, evidence.lineage_digest,
                       evidence.support_score_ppm, evidence.conflict_score_ppm
                from source_evidence evidence
                join research_cell_evidence binding on binding.source_evidence_id = evidence.id
                join research_cell cell on cell.id = binding.research_cell_id and cell.cell_status = 'VERIFIED'
                where evidence.research_run_id = ? order by evidence.evidence_key
                """, (rs, rowNum) -> map(
                "evidence_key", rs.getString("evidence_key"), "window_id", rs.getString("window_id"),
                "source_id", rs.getString("source_id"), "source_title", rs.getString("source_title"),
                "source_url", rs.getString("source_url"), "provider", rs.getString("provider"),
                "adapter", rs.getString("adapter"), "search_query", rs.getString("search_query"),
                "read_focus", rs.getString("read_focus"), "quote_text", rs.getString("quote_text"),
                "claim_text", rs.getString("claim_text"), "relation_type", rs.getString("relation_type"),
                "support_score", rs.getBigDecimal("support_score"), "conflict_score", rs.getBigDecimal("conflict_score"),
                "snapshot_status", rs.getString("snapshot_status"), "snapshot_key", rs.getString("snapshot_key"),
                "source_origin", rs.getString("source_origin"), "source_domain", rs.getString("source_domain"),
                "lineage_digest", rs.getString("lineage_digest"),
                "support_score_ppm", rs.getObject("support_score_ppm"),
                "conflict_score_ppm", rs.getObject("conflict_score_ppm")), runId);
    }

    private List<Map<String, Object>> openDecisions(String runId) {
        return jdbcTemplate.query("""
                select decision_scope, decision_type, reason_code, target_id, evidence_ids_json,
                       action_text, decision_status, notes_json
                from research_verifier_decision
                where research_run_id = ? and decision_status = 'OPEN'
                order by decision_scope, decision_type, target_id, id
                """, (rs, rowNum) -> map(
                "decision_scope", rs.getString("decision_scope"), "decision_type", rs.getString("decision_type"),
                "reason_code", rs.getString("reason_code"), "target_id", rs.getString("target_id"),
                "evidence_ids", readJson(rs.getString("evidence_ids_json"), List.of()),
                "action_text", rs.getString("action_text"), "decision_status", rs.getString("decision_status"),
                "notes", readJson(rs.getString("notes_json"), List.of())), runId);
    }

    private List<Map<String, Object>> stages(String runId) {
        return jdbcTemplate.query("""
                select stage, stage_revision, status, barrier_digest, expected_task_count,
                       settled_task_count, blocker_count, stage_version, barrier_json
                from research_run_stage where research_run_id = ? order by stage, stage_revision
                """, (rs, rowNum) -> map(
                "stage", rs.getString("stage"), "stage_revision", rs.getInt("stage_revision"),
                "status", rs.getString("status"), "barrier_digest", rs.getString("barrier_digest"),
                "expected_task_count", rs.getInt("expected_task_count"),
                "settled_task_count", rs.getInt("settled_task_count"), "blocker_count", rs.getInt("blocker_count"),
                "stage_version", rs.getString("stage_version"),
                "barrier", readJson(rs.getString("barrier_json"), Map.of())), runId);
    }

    private Object latestJson(String sql, String runId) {
        String raw = jdbcTemplate.query(sql, rs -> rs.next() ? rs.getString(1) : null, runId);
        return readJson(raw, Map.of());
    }

    private Object readJson(String raw, Object fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Canonical checkpoint source JSON is invalid", exception);
        }
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record CompiledSnapshot(String canonicalJson, int contentSize, String payloadSha256, String canonicalDigest) { }
}
