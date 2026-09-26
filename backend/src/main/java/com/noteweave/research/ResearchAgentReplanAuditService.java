package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * DR-203: persists and reads back the auditable decision summary of a Research Plan Replan.
 *
 * <p>Only what the architecture document allows is accepted: the deviation type, the observable
 * facts, the affected cells, the budget delta, the Evidence/Receipt references, the selected
 * repair and the old/new plan digests. Model text and reasoning chains are rejected by
 * construction - every text field has a hard length bound and the payload never reaches the
 * database when a bound is exceeded.
 *
 * <p>This service is deliberately trigger-agnostic: it does not decide when a Replan happens nor
 * where inside a caller transaction the audit row is written. Wiring it into a concrete Replan
 * trigger (Java side) is a separate, explicitly arbitrated decision.
 */
@Service
public class ResearchAgentReplanAuditService {

    /**
     * Budget counter names aligned with the Worker's {@code _RESERVATION_KEYS}: the seven worker
     * usage counters from {@link ResearchAgentCompletionCanonicalizer#WORKER_USAGE_KEYS} plus the
     * three server-derived counters. A delta using any other counter name is rejected.
     */
    static final Set<String> BUDGET_DELTA_KEYS = Set.of(
            "llm_calls", "search_calls", "fetch_calls", "read_calls", "extract_calls",
            "evidence_cards", "candidates_submitted",
            "evidence_appended", "candidate_merges_accepted", "candidate_merges_rejected"
    );

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern CELL_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:+-]{0,159}");
    private static final Pattern REFERENCE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:+-]{0,159}");
    private static final Pattern COUNTER_KEY = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    static final int MAX_RUN_ID_CHARS = 36;
    static final int MAX_OBSERVED_FACTS_CHARS = 512;
    static final int MAX_SELECTED_REPAIR_CHARS = 512;
    static final int MAX_AFFECTED_CELLS = 80;
    static final int MAX_EVIDENCE_REFS = 64;
    static final long MAX_BUDGET_DELTA_MAGNITUDE = 1_000_000L;
    /** Upper bound of each serialized JSON payload, so every stored field stays length-capped. */
    static final int MAX_PAYLOAD_CHARS = 4_096;

    private static final String SELECT_COLUMNS = """
            select id, research_run_id, plan_revision_from, plan_revision_to, old_plan_digest,
                   new_plan_digest, deviation_type, observed_facts, affected_cells_json,
                   budget_delta_json, evidence_refs_json, selected_repair, created_at
            from research_agent_replan_audit
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    ResearchAgentReplanAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Persists one Replan audit row.
     *
     * @throws BusinessException when the decision summary is outside the contract, or when a row
     *         already exists for the same run and resulting plan revision (idempotency key)
     */
    public ReplanAudit recordReplan(ReplanAuditCommand command) {
        String runId = requireRunId(command);
        int revisionFrom = command.planRevisionFrom();
        int revisionTo = command.planRevisionTo();
        if (revisionFrom < 0 || revisionTo <= revisionFrom) {
            throw invalid("RESEARCH_REPLAN_AUDIT_INVALID", "Replan revision range is invalid");
        }
        String oldDigest = requireDigest(command.oldPlanDigest(), "old_plan_digest");
        String newDigest = requireDigest(command.newPlanDigest(), "new_plan_digest");
        if (oldDigest.equals(newDigest)) {
            throw invalid("RESEARCH_REPLAN_DIGEST_UNCHANGED",
                    "A Replan without an observable plan change cannot be persisted");
        }
        String deviationType = ResearchReplanDeviationTypes.validate(command.deviationType());
        String observedFacts = boundedText(command.observedFacts(), MAX_OBSERVED_FACTS_CHARS,
                "RESEARCH_REPLAN_OBSERVED_FACTS_INVALID", "observed_facts");
        List<String> affectedCells = requireAffectedCells(command.affectedCells());
        Map<String, Long> budgetDelta = requireBudgetDelta(command.budgetDelta());
        List<String> evidenceRefs = optionalReferences(command.evidenceRefs());
        String selectedRepair = boundedText(command.selectedRepair(), MAX_SELECTED_REPAIR_CHARS,
                "RESEARCH_REPLAN_SELECTED_REPAIR_INVALID", "selected_repair");

        String affectedCellsJson = boundedPayload(Json.write(objectMapper, affectedCells),
                "RESEARCH_REPLAN_AFFECTED_CELLS_INVALID", "affected_cells_json");
        String budgetDeltaJson = boundedPayload(Json.write(objectMapper, budgetDelta),
                "RESEARCH_REPLAN_BUDGET_DELTA_INVALID", "budget_delta_json");
        String evidenceRefsJson = evidenceRefs == null ? null
                : boundedPayload(Json.write(objectMapper, evidenceRefs),
                        "RESEARCH_REPLAN_EVIDENCE_REFS_INVALID", "evidence_refs_json");

        String id = Ids.newId();
        try {
            jdbcTemplate.update("""
                    insert into research_agent_replan_audit(
                        id, research_run_id, plan_revision_from, plan_revision_to, old_plan_digest,
                        new_plan_digest, deviation_type, observed_facts, affected_cells_json,
                        budget_delta_json, evidence_refs_json, selected_repair)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, runId, revisionFrom, revisionTo, oldDigest, newDigest, deviationType,
                    observedFacts, affectedCellsJson, budgetDeltaJson, evidenceRefsJson, selectedRepair);
        } catch (DataIntegrityViolationException failure) {
            Integer existing = jdbcTemplate.queryForObject("""
                    select count(*) from research_agent_replan_audit
                    where research_run_id = ? and plan_revision_to = ?
                    """, Integer.class, runId, revisionTo);
            if (existing != null && existing > 0) {
                throw invalid("RESEARCH_REPLAN_AUDIT_REVISION_CONFLICT",
                        "A Replan audit row already exists for this run and plan revision");
            }
            throw failure;
        }
        return new ReplanAudit(id, runId, revisionFrom, revisionTo, oldDigest, newDigest,
                deviationType, observedFacts, affectedCells, budgetDelta, evidenceRefs,
                selectedRepair, Instant.now());
    }

    /** All Replan audit rows of a run, ordered by the resulting plan revision. */
    public List<ReplanAudit> findByRun(String runId) {
        if (runId == null || runId.isBlank() || runId.strip().length() > MAX_RUN_ID_CHARS) {
            throw invalid("RESEARCH_REPLAN_AUDIT_INVALID", "Replan audit run id is invalid");
        }
        return jdbcTemplate.query(SELECT_COLUMNS + " where research_run_id = ? order by plan_revision_to",
                this::mapRow, runId.strip());
    }

    /** The Replan audit row for one resulting plan revision, or {@code null} when absent. */
    public ReplanAudit findByRunAndRevision(String runId, int planRevisionTo) {
        if (runId == null || runId.isBlank() || runId.strip().length() > MAX_RUN_ID_CHARS) {
            throw invalid("RESEARCH_REPLAN_AUDIT_INVALID", "Replan audit run id is invalid");
        }
        return jdbcTemplate.query(SELECT_COLUMNS + " where research_run_id = ? and plan_revision_to = ?",
                rs -> rs.next() ? mapRow(rs, 0) : null, runId.strip(), planRevisionTo);
    }

    private ReplanAudit mapRow(ResultSet rs, int rowNum) throws SQLException {
        String evidenceRefs = rs.getString("evidence_refs_json");
        return new ReplanAudit(
                rs.getString("id"),
                rs.getString("research_run_id"),
                rs.getInt("plan_revision_from"),
                rs.getInt("plan_revision_to"),
                rs.getString("old_plan_digest"),
                rs.getString("new_plan_digest"),
                rs.getString("deviation_type"),
                rs.getString("observed_facts"),
                readStringList(rs.getString("affected_cells_json"), "affected_cells_json"),
                readLongMap(rs.getString("budget_delta_json")),
                evidenceRefs == null ? null : readStringList(evidenceRefs, "evidence_refs_json"),
                rs.getString("selected_repair"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private String requireRunId(ReplanAuditCommand command) {
        if (command == null) {
            throw invalid("RESEARCH_REPLAN_AUDIT_INVALID", "Replan audit command is required");
        }
        String runId = command.researchRunId() == null ? "" : command.researchRunId().strip();
        if (runId.isEmpty() || runId.length() > MAX_RUN_ID_CHARS) {
            throw invalid("RESEARCH_REPLAN_AUDIT_INVALID", "Replan audit run id is invalid");
        }
        return runId;
    }

    private String requireDigest(String digest, String field) {
        String normalized = digest == null ? "" : digest.strip();
        if (!DIGEST.matcher(normalized).matches()) {
            throw invalid("RESEARCH_REPLAN_DIGEST_INVALID", field + " must be a sha256 digest");
        }
        return normalized;
    }

    private String boundedText(String value, int maxChars, String code, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxChars) {
            throw invalid(code, field + " must be a bounded non-empty decision summary");
        }
        return normalized;
    }

    private String boundedPayload(String json, String code, String field) {
        if (json.length() > MAX_PAYLOAD_CHARS) {
            throw invalid(code, field + " exceeds the bounded Replan decision payload size");
        }
        return json;
    }

    private List<String> requireAffectedCells(List<String> cells) {
        if (cells == null || cells.isEmpty() || cells.size() > MAX_AFFECTED_CELLS) {
            throw invalid("RESEARCH_REPLAN_AFFECTED_CELLS_INVALID",
                    "A Replan must name its affected cells");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String cell : cells) {
            String normalized = cell == null ? "" : cell.strip();
            if (!CELL_KEY.matcher(normalized).matches()) {
                throw invalid("RESEARCH_REPLAN_AFFECTED_CELLS_INVALID",
                        "Replan affected cell key is malformed");
            }
            unique.add(normalized);
        }
        if (unique.size() != cells.size()) {
            throw invalid("RESEARCH_REPLAN_AFFECTED_CELLS_INVALID",
                    "Replan affected cells must be unique");
        }
        return List.copyOf(unique);
    }

    private Map<String, Long> requireBudgetDelta(Map<String, Long> delta) {
        if (delta == null) {
            throw invalid("RESEARCH_REPLAN_BUDGET_DELTA_INVALID", "Replan budget delta is required");
        }
        Map<String, Long> normalized = new TreeMap<>();
        for (Map.Entry<String, Long> entry : delta.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().strip();
            Long value = entry.getValue();
            if (!COUNTER_KEY.matcher(key).matches() || !BUDGET_DELTA_KEYS.contains(key)) {
                throw invalid("RESEARCH_REPLAN_BUDGET_DELTA_INVALID",
                        "Replan budget delta key is not a known reservation counter");
            }
            if (value == null || Math.abs(value) > MAX_BUDGET_DELTA_MAGNITUDE) {
                throw invalid("RESEARCH_REPLAN_BUDGET_DELTA_INVALID",
                        "Replan budget delta value is out of range");
            }
            normalized.put(key, value);
        }
        // Sorted and unmodifiable so the persisted JSON payload is reproducible for audits.
        return java.util.Collections.unmodifiableMap(normalized);
    }

    private List<String> optionalReferences(List<String> refs) {
        if (refs == null) {
            return null;
        }
        if (refs.isEmpty() || refs.size() > MAX_EVIDENCE_REFS) {
            throw invalid("RESEARCH_REPLAN_EVIDENCE_REFS_INVALID",
                    "Replan evidence references must be absent or a bounded non-empty list");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String ref : refs) {
            String normalized = ref == null ? "" : ref.strip();
            if (!REFERENCE_KEY.matcher(normalized).matches()) {
                throw invalid("RESEARCH_REPLAN_EVIDENCE_REFS_INVALID",
                        "Replan evidence reference is malformed");
            }
            unique.add(normalized);
        }
        return List.copyOf(unique);
    }

    private List<String> readStringList(String json, String field) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Replan audit " + field + " is corrupt", exception);
        }
    }

    private Map<String, Long> readLongMap(String json) {
        try {
            return new TreeMap<>(objectMapper.readValue(json, new TypeReference<Map<String, Long>>() { }));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Replan audit budget_delta_json is corrupt", exception);
        }
    }

    private BusinessException invalid(String code, String message) {
        return new BusinessException(code, message);
    }

    /**
     * The Replan decision summary. Field names intentionally avoid any free-form reasoning slot.
     */
    public record ReplanAuditCommand(
            String researchRunId,
            int planRevisionFrom,
            int planRevisionTo,
            String oldPlanDigest,
            String newPlanDigest,
            String deviationType,
            String observedFacts,
            List<String> affectedCells,
            Map<String, Long> budgetDelta,
            List<String> evidenceRefs,
            String selectedRepair
    ) { }

    /** One persisted Replan audit row. */
    public record ReplanAudit(
            String id,
            String researchRunId,
            int planRevisionFrom,
            int planRevisionTo,
            String oldPlanDigest,
            String newPlanDigest,
            String deviationType,
            String observedFacts,
            List<String> affectedCells,
            Map<String, Long> budgetDelta,
            List<String> evidenceRefs,
            String selectedRepair,
            Instant createdAt
    ) { }
}
