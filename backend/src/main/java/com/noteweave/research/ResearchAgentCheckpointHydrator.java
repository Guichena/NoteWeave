package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Hydrates a descendant canonical ledger without copying execution-plane ownership. */
@Service
class ResearchAgentCheckpointHydrator {
    private static final String DIGEST_DOMAIN = "research-agent-checkpoint-hydration.v1";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final ResearchAgentCheckpointSnapshotCompiler compiler;
    private final boolean enabled;
    private final ResearchAgentFeatureFlagService featureFlags;

    ResearchAgentCheckpointHydrator(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchAgentCheckpointSnapshotCompiler compiler,
            ResearchAgentFeatureFlagService featureFlags,
            @Value("${noteweave.research.checkpoint-hydration-v2:false}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.compiler = compiler;
        this.featureFlags = featureFlags;
        this.enabled = enabled;
    }

    boolean available(String workspaceId, String runId, int checkpointSeq) {
        if (!enabled || !featureFlags.enabledForRun(
                runId, ResearchAgentFeatureFlagService.CHECKPOINT_HYDRATION)) return false;
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from research_checkpoint_hydration_snapshot snapshot
                join research_run run on run.id = snapshot.research_run_id
                where run.workspace_id = ? and run.id = ? and snapshot.checkpoint_seq = ?
                  and snapshot.schema_version = ?
                """, Integer.class, workspaceId, runId, checkpointSeq,
                ResearchAgentCheckpointSnapshotCompiler.SCHEMA_VERSION);
        return count != null && count == 1;
    }

    @Transactional
    public HydrationReceipt hydrate(
            String workspaceId,
            String sourceRunId,
            int checkpointSeq,
            String descendantRunId
    ) {
        requireAuthorized(sourceRunId);
        String lockedWorkspace = jdbcTemplate.query("""
                select workspace_id from research_run where id = ? for update
                """, rs -> rs.next() ? rs.getString(1) : null, sourceRunId);
        if (!workspaceId.equals(lockedWorkspace)) {
            throw new BusinessException("RESEARCH_CHECKPOINT_NOT_FOUND", "Hydration source checkpoint does not exist");
        }
        SnapshotRow snapshot = jdbcTemplate.query("""
                select checkpoint_id, payload_json, content_size, payload_sha256,
                       canonical_digest, ledger_digest, schema_version
                from research_checkpoint_hydration_snapshot
                where research_run_id = ? and checkpoint_seq = ? for update
                """, rs -> rs.next() ? new SnapshotRow(
                rs.getString("checkpoint_id"), rs.getString("payload_json"), rs.getLong("content_size"),
                rs.getString("payload_sha256"), rs.getString("canonical_digest"),
                rs.getString("ledger_digest"), rs.getString("schema_version")) : null,
                sourceRunId, checkpointSeq);
        if (snapshot == null || !ResearchAgentCheckpointSnapshotCompiler.SCHEMA_VERSION.equals(snapshot.schemaVersion())) {
            throw new BusinessException("RESEARCH_CHECKPOINT_HYDRATION_REQUIRED",
                    "Checkpoint has no supported canonical-ledger hydration payload");
        }
        requireNoPriorHydration(sourceRunId, checkpointSeq);
        Map<String, Object> payload = verify(snapshot);
        if (!sourceRunId.equals(text(payload.get("source_research_run_id")))
                || !snapshot.checkpointId().equals(text(payload.get("source_checkpoint_id")))
                || checkpointSeq != nonNegativeInt(payload.get("checkpoint_seq"), -1)
                || !snapshot.ledgerDigest().equals(text(payload.get("ledger_digest")))) {
            throw new BusinessException("RESEARCH_CHECKPOINT_CORRUPTED",
                    "Hydration payload identity does not match its checkpoint row");
        }
        // plan_revision / entity_set_version are the CAS identity of every restored Cell: the
        // task coordinator requires task and cell scope to be exactly equal. A missing source
        // value must fail here instead of defaulting to 0, because a 0-defaulted Cell can never
        // bind a task and would silently freeze the Run.
        int planRevision = requiredNonNegativeInt(payload.get("plan_revision"), "plan_revision");
        int entitySetVersion = requiredNonNegativeInt(payload.get("entity_set_version"), "entity_set_version");
        claimHydration(sourceRunId, checkpointSeq, snapshot.ledgerDigest(), descendantRunId);
        Map<String, String> branchIds = hydrateBranches(descendantRunId, listOfMaps(payload.get("branches")));
        Map<String, String> rowIds = hydrateRows(descendantRunId, listOfMaps(payload.get("rows")), branchIds);
        Map<String, String> cellIds = hydrateCells(descendantRunId, listOfMaps(payload.get("cells")), branchIds, rowIds);
        Map<String, String> evidenceIds = hydrateEvidence(
                descendantRunId, listOfMaps(payload.get("accepted_evidence")));
        hydrateCellEvidence(descendantRunId, listOfMaps(payload.get("cells")), cellIds, evidenceIds);
        hydrateDecisions(descendantRunId, listOfMaps(payload.get("open_decisions")));
        hydrateStages(descendantRunId, listOfMaps(payload.get("stages")));
        hydrateMatrixPlan(descendantRunId, sourceRunId, mapValue(payload.get("matrix_plan")));

        String genesisId = Ids.newId();
        int waveNo = positiveInt(payload.get("wave_no"), 1);
        Map<String, Object> sourceBudget = mapValue(payload.get("budget_summary"));
        Map<String, Object> genesisSummary = Map.of(
                "resume_mode", "HYDRATED_LEDGER_RESUME",
                "source_research_run_id", sourceRunId,
                "source_checkpoint_seq", checkpointSeq,
                "source_ledger_digest", snapshot.ledgerDigest());
        jdbcTemplate.update("""
                insert into research_agent_checkpoint(
                    id, research_run_id, checkpoint_seq, wave_no, round_no, plan_revision,
                    entity_set_version, ledger_hash, task_high_water_mark, candidate_high_water_mark,
                    merge_high_water_mark, budget_summary_json, summary_json)
                values (?, ?, 1, ?, 1, ?, ?, ?, 0, 0, 0, ?, ?)
                """, genesisId, descendantRunId, waveNo, planRevision, entitySetVersion,
                snapshot.ledgerDigest(), Json.write(objectMapper, Map.of("restored_source", sourceBudget)),
                Json.write(objectMapper, genesisSummary));
        compiler.compile(genesisId, 1, new ResearchBudgetAndCheckpointService.CheckpointCommand(
                descendantRunId, waveNo, 1, planRevision, entitySetVersion, snapshot.ledgerDigest(),
                0, 0, 0, Map.of("restored_source", sourceBudget), genesisSummary));
        return new HydrationReceipt(cellIds.size(), evidenceIds.size(), 1, snapshot.ledgerDigest());
    }

    private Map<String, Object> verify(SnapshotRow snapshot) {
        byte[] bytes = snapshot.payloadJson().getBytes(StandardCharsets.UTF_8);
        if (bytes.length != snapshot.contentSize() || !sha256(bytes).equalsIgnoreCase(snapshot.payloadSha256())) {
            throw new BusinessException("RESEARCH_CHECKPOINT_CORRUPTED",
                    "Hydration payload does not match its persisted size and SHA-256");
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(snapshot.payloadJson(), new TypeReference<>() { });
            // payload_json is stored already canonicalized and the compiler's digest is
            // sha256(domain + "\n" + those canonical bytes). Re-canonicalizing the parsed Map does
            // not reproduce it, because a JSON round trip changes Java types (BigDecimal scale,
            // timestamps), so verify against the persisted bytes themselves.
            byte[] prefix = (DIGEST_DOMAIN + "\n").getBytes(StandardCharsets.US_ASCII);
            byte[] digestInput = new byte[prefix.length + bytes.length];
            System.arraycopy(prefix, 0, digestInput, 0, prefix.length);
            System.arraycopy(bytes, 0, digestInput, prefix.length, bytes.length);
            String canonicalDigest = "sha256:" + sha256(digestInput);
            if (!canonicalDigest.equalsIgnoreCase(snapshot.canonicalDigest())) {
                throw new BusinessException("RESEARCH_CHECKPOINT_CORRUPTED",
                        "Hydration payload canonical digest is invalid");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new BusinessException("RESEARCH_CHECKPOINT_CORRUPTED", "Hydration payload JSON is invalid");
        }
    }

    /**
     * Fail-closed authorization. {@link #available} gates the resume entry point, but hydration
     * must not silently restore a ledger for a caller that bypasses that gate, so the two feature
     * flag layers (deployment switch + the source Run's captured snapshot) are re-checked here.
     */
    private void requireAuthorized(String sourceRunId) {
        if (!enabled || !featureFlags.enabledForRun(
                sourceRunId, ResearchAgentFeatureFlagService.CHECKPOINT_HYDRATION)) {
            throw new BusinessException("RESEARCH_CHECKPOINT_HYDRATION_DISABLED",
                    "Checkpoint hydration is not enabled for this run");
        }
    }

    /**
     * Explicit replay detection keyed on (source run, source checkpoint). A duplicate hydration
     * must fail deterministically here - it must never depend on a copied table happening to
     * carry a unique index (research_verifier_decision has only a plain index).
     */
    private void requireNoPriorHydration(String sourceRunId, int checkpointSeq) {
        Integer prior = jdbcTemplate.queryForObject("""
                select count(*) from research_run
                where hydrated_from_research_run_id = ? and hydrated_from_checkpoint_seq = ?
                """, Integer.class, sourceRunId, checkpointSeq);
        if (prior != null && prior > 0) {
            throw new BusinessException("RESEARCH_CHECKPOINT_HYDRATION_REPLAY",
                    "Checkpoint has already been hydrated into a descendant run");
        }
    }

    /** Persists the idempotency key on the descendant Run before any ledger row is copied. */
    private void claimHydration(
            String sourceRunId,
            int checkpointSeq,
            String sourceLedgerDigest,
            String descendantRunId
    ) {
        int claimed;
        try {
            claimed = jdbcTemplate.update("""
                    update research_run
                    set hydrated_from_research_run_id = ?, hydrated_from_checkpoint_seq = ?,
                        hydrated_source_ledger_digest = ?, updated_at = current_timestamp
                    where id = ?
                    """, sourceRunId, checkpointSeq, sourceLedgerDigest, descendantRunId);
        } catch (DataIntegrityViolationException concurrentClaim) {
            // A racing hydration won the unique (source run, source checkpoint) key: surface the
            // same stable replay code as the deterministic pre-read instead of a raw SQL error.
            throw new BusinessException("RESEARCH_CHECKPOINT_HYDRATION_REPLAY",
                    "Checkpoint has already been hydrated into a descendant run");
        }
        if (claimed != 1) {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "Hydration descendant run does not exist");
        }
    }

    private Map<String, String> hydrateBranches(
            String runId,
            List<Map<String, Object>> branches
    ) {
        Map<String, String> ids = new LinkedHashMap<>();
        branches.forEach(branch -> ids.put(text(branch.get("branch_key")), Ids.newId()));
        for (Map<String, Object> branch : branches) {
            String key = text(branch.get("branch_key"));
            String parent = ids.get(text(branch.get("parent_branch_key")));
            jdbcTemplate.update("""
                    insert into research_branch(
                        id, research_run_id, branch_key, parent_branch_id, branch_reason,
                        branch_status, hypothesis_summary, target_evidence_ids_json, created_round)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, ids.get(key), runId, key, parent, text(branch.get("branch_reason")),
                    text(branch.get("branch_status")), nullableText(branch.get("hypothesis_summary")),
                    Json.write(objectMapper, listValue(branch.get("target_evidence_ids"))),
                    positiveInt(branch.get("created_round"), 1));
        }
        return ids;
    }

    private Map<String, String> hydrateRows(
            String runId,
            List<Map<String, Object>> rows,
            Map<String, String> branchIds
    ) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = text(row.get("row_key"));
            String id = Ids.newId();
            ids.put(key, id);
            jdbcTemplate.update("""
                    insert into research_row(
                        id, research_run_id, row_key, branch_id, source_id, source_title,
                        search_query, read_focus, evidence_id, row_status, relation_type,
                        support_score, conflict_score, support_level, verification_status,
                        verifier_note, repair_hint)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, runId, key, branchIds.get(text(row.get("branch_key"))),
                    nullableText(row.get("source_id")), nullableText(row.get("source_title")),
                    nullableText(row.get("search_query")), nullableText(row.get("read_focus")),
                    nullableText(row.get("evidence_id")), text(row.get("row_status")),
                    nullableText(row.get("relation_type")), row.get("support_score"), row.get("conflict_score"),
                    nullableText(row.get("support_level")), nullableText(row.get("verification_status")),
                    nullableText(row.get("verifier_note")), nullableText(row.get("repair_hint")));
        }
        return ids;
    }

    private Map<String, String> hydrateCells(
            String runId,
            List<Map<String, Object>> cells,
            Map<String, String> branchIds,
            Map<String, String> rowIds
    ) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (Map<String, Object> cell : cells) {
            String key = text(cell.get("cell_key"));
            String id = Ids.newId();
            ids.put(key, id);
            String status = text(cell.get("cell_status"));
            if (status.isBlank()) status = "GAP";
            jdbcTemplate.update("""
                    insert into research_cell(
                        id, research_run_id, research_row_id, cell_key, branch_id, column_key,
                        candidate_value, cell_status, confidence_score, confidence_score_ppm,
                        evidence_refs_json, last_verifier_decision, repair_count, cell_version,
                        plan_revision, entity_set_version, last_merge_id, active_task_id,
                        lease_epoch, fencing_token, high_risk)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, 0, 0, ?)
                    """, id, runId, rowIds.get(text(cell.get("row_key"))), key,
                    branchIds.get(text(cell.get("branch_key"))), text(cell.get("column_key")),
                    nullableText(cell.get("candidate_value")), status, cell.get("confidence_score"),
                    cell.get("confidence_score_ppm"), Json.write(objectMapper, listValue(cell.get("evidence_refs"))),
                    nullableText(cell.get("last_verifier_decision")), nonNegativeInt(cell.get("repair_count"), 0),
                    nonNegativeInt(cell.get("cell_version"), 0),
                    requiredNonNegativeInt(cell.get("plan_revision"), "cells[].plan_revision"),
                    requiredNonNegativeInt(cell.get("entity_set_version"), "cells[].entity_set_version"), null,
                    booleanValue(cell.get("high_risk")));
        }
        return ids;
    }

    private Map<String, String> hydrateEvidence(String runId, List<Map<String, Object>> evidence) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (Map<String, Object> item : evidence) {
            String key = text(item.get("evidence_key"));
            String id = Ids.newId();
            ids.put(key, id);
            jdbcTemplate.update("""
                    insert into source_evidence(
                        id, research_run_id, evidence_key, window_id, source_id, source_title,
                        source_url, provider, adapter, search_query, read_focus, quote_text, claim_text,
                        relation_type, support_score, conflict_score, snapshot_status, snapshot_key,
                        source_origin, source_domain, lineage_digest, support_score_ppm, conflict_score_ppm,
                        agent_completion_id, content_digest)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, null)
                    """, id, runId, key, nullableText(item.get("window_id")), nullableText(item.get("source_id")),
                    nullableText(item.get("source_title")), nullableText(item.get("source_url")),
                    nullableText(item.get("provider")), nullableText(item.get("adapter")),
                    nullableText(item.get("search_query")), nullableText(item.get("read_focus")),
                    nullableText(item.get("quote_text")), nullableText(item.get("claim_text")),
                    nullableText(item.get("relation_type")), item.get("support_score"), item.get("conflict_score"),
                    nullableText(item.get("snapshot_status")), nullableText(item.get("snapshot_key")),
                    nullableText(item.get("source_origin")), nullableText(item.get("source_domain")),
                    nullableText(item.get("lineage_digest")), item.get("support_score_ppm"), item.get("conflict_score_ppm"));
        }
        return ids;
    }

    private void hydrateCellEvidence(
            String runId,
            List<Map<String, Object>> cells,
            Map<String, String> cellIds,
            Map<String, String> evidenceIds
    ) {
        for (Map<String, Object> cell : cells) {
            String cellId = cellIds.get(text(cell.get("cell_key")));
            for (Object rawKey : listValue(cell.get("evidence_refs"))) {
                String key = text(rawKey);
                String evidenceId = evidenceIds.get(key);
                if (evidenceId == null) continue;
                jdbcTemplate.update("""
                        insert into research_cell_evidence(
                            id, research_run_id, research_cell_id, source_evidence_id,
                            evidence_key, agent_completion_id, content_digest)
                        values (?, ?, ?, ?, ?, null, null)
                        """, Ids.newId(), runId, cellId, evidenceId, key);
            }
        }
    }

    private void hydrateDecisions(String runId, List<Map<String, Object>> decisions) {
        for (Map<String, Object> item : decisions) {
            jdbcTemplate.update("""
                    insert into research_verifier_decision(
                        id, research_run_id, decision_scope, decision_type, reason_code,
                        target_id, evidence_ids_json, action_text, decision_status, notes_json)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), runId, text(item.get("decision_scope")), text(item.get("decision_type")),
                    text(item.get("reason_code")), nullableText(item.get("target_id")),
                    Json.write(objectMapper, listValue(item.get("evidence_ids"))),
                    nullableText(item.get("action_text")), "OPEN",
                    Json.write(objectMapper, listValue(item.get("notes"))));
        }
    }

    /**
     * The source barrier must not be copied verbatim: it references the source Run and source task
     * counts, and its status is outside the legal vocabulary (ACTIVE / BARRIER_PENDING / SETTLED)
     * that {@link ResearchAgentRunnableWorkService} owns. The descendant barrier is therefore
     * recomputed from the descendant ledger only, with execution-plane counts reset to zero
     * because no research_agent_task is copied. This also removes the dead 'HYDRATED' status that
     * no query ever read.
     */
    private void hydrateStages(String runId, List<Map<String, Object>> stages) {
        for (Map<String, Object> item : stages) {
            String stage = text(item.get("stage"));
            int stageRevision = positiveInt(item.get("stage_revision"), 1);
            int blockerCount = ResearchAgentRunnableWorkService.blockedCellCount(jdbcTemplate, runId);
            int runnableCount = ResearchAgentRunnableWorkService.runnableCellCount(jdbcTemplate, runId);
            String status = runnableCount == 0 && blockerCount == 0 ? "SETTLED" : "BARRIER_PENDING";
            Map<String, Object> barrier = new LinkedHashMap<>();
            barrier.put("run_id", runId);
            barrier.put("stage", stage);
            barrier.put("stage_revision", stageRevision);
            barrier.put("status", status);
            barrier.put("expected_task_count", 0);
            barrier.put("settled_task_count", 0);
            barrier.put("blocker_count", blockerCount);
            // Hydration always writes the genesis checkpoint at sequence 1 in this same transaction.
            barrier.put("checkpoint_seq", 1);
            jdbcTemplate.update("""
                    insert into research_run_stage(
                        id, research_run_id, stage, stage_revision, status, barrier_digest,
                        expected_task_count, settled_task_count, blocker_count, stage_version, barrier_json)
                    values (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)
                    """, Ids.newId(), runId, stage, stageRevision, status,
                    ResearchAgentRunnableWorkService.barrierDigest(canonicalizer, barrier),
                    blockerCount, ResearchAgentRunnableWorkService.STAGE_VERSION,
                    Json.write(objectMapper, barrier));
        }
    }

    private void hydrateMatrixPlan(String runId, String sourceRunId, Map<String, Object> snapshotPlan) {
        Map<String, Object> plan = snapshotPlan;
        if (listValue(plan.get("rows")).isEmpty() || listValue(plan.get("columns")).isEmpty()) {
            // Older snapshots captured the latest plan row, which can be a LOCAL_REPAIR record
            // (no rows/columns). The matrix itself is the source Run's base plan.
            plan = sourceBasePlan(sourceRunId);
        }
        if (plan.isEmpty()) return;
        List<?> rows = listValue(plan.get("rows"));
        List<?> columns = listValue(plan.get("columns"));
        int cellCount = nonNegativeInt(plan.get("cell_count"), rows.size() * columns.size());
        String digest = canonicalizer.domainSeparatedDigest("research-matrix-plan.v2", plan);
        jdbcTemplate.update("""
                insert into research_matrix_plan(
                    id, research_run_id, planner_version, plan_mode, plan_status,
                    row_count, column_count, cell_count, bounded, reason_codes_json,
                    plan_json, plan_digest)
                values (?, ?, ?, 'INTENT_MATRIX_V2', 'HYDRATED', ?, ?, ?, ?, ?, ?, ?)
                """, Ids.newId(), runId, text(plan.get("planner_version")), rows.size(), columns.size(), cellCount,
                booleanValue(plan.get("bounded")), Json.write(objectMapper, listValue(plan.get("reason_codes"))),
                Json.write(objectMapper, plan), digest);
    }

    private Map<String, Object> sourceBasePlan(String sourceRunId) {
        List<String> plans = jdbcTemplate.queryForList("""
                select plan_json from research_matrix_plan
                where research_run_id = ? and plan_mode <> 'LOCAL_REPAIR'
                order by created_at desc, id desc limit 1
                """, String.class, sourceRunId);
        if (plans.isEmpty() || plans.get(0) == null) return Map.of();
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(plans.get(0));
            if (node.isTextual()) node = objectMapper.readTree(node.asText());
            if (!node.isObject()) return Map.of();
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<?> listValue(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).strip(); }
    private String nullableText(Object value) { String text = text(value); return text.isBlank() ? null : text; }
    private boolean booleanValue(Object value) { return value instanceof Boolean flag ? flag : Boolean.parseBoolean(text(value)); }
    private int positiveInt(Object value, int fallback) { int result = nonNegativeInt(value, fallback); return result < 1 ? fallback : result; }
    private int nonNegativeInt(Object value, int fallback) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        try { return Math.max(0, Integer.parseInt(text(value))); } catch (NumberFormatException ignored) { return fallback; }
    }

    /**
     * Fail-closed variant for values the restored ledger cannot invent. Unlike
     * {@link #nonNegativeInt(Object, int)} a missing or non-numeric source value is a hard error,
     * not a 0 default.
     */
    private int requiredNonNegativeInt(Object value, String field) {
        if (value instanceof Number number) {
            int parsed = number.intValue();
            if (parsed >= 0) return parsed;
        } else {
            String raw = text(value);
            if (!raw.isBlank()) {
                try {
                    int parsed = Integer.parseInt(raw);
                    if (parsed >= 0) return parsed;
                } catch (NumberFormatException ignored) {
                    // fall through to the explicit failure below
                }
            }
        }
        throw new BusinessException("RESEARCH_CHECKPOINT_HYDRATION_IDENTITY_MISSING",
                "Hydration payload has a missing or invalid " + field);
    }
    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    record HydrationReceipt(int restoredCellCount, int restoredEvidenceCount, int genesisCheckpointSeq,
                            String sourceLedgerDigest) { }
    private record SnapshotRow(String checkpointId, String payloadJson, long contentSize, String payloadSha256,
                               String canonicalDigest, String ledgerDigest, String schemaVersion) { }
}
