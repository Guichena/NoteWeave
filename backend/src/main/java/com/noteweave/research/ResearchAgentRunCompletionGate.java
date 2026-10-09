package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Json;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DR-305: the single authority for a Run's business terminal state.
 *
 * <p>The plan (§7.3) requires the gate to allow partial results to finish honestly, so a Run whose
 * evidence is genuinely missing must never be flattened into an opaque barrier exception or into an
 * infrastructure failure. The decision is a pure function of already-persisted canonical state:
 *
 * <pre>
 * evaluate(tableState, verifierState, budgetState, infraState) -&gt; CompletionDecision
 * </pre>
 *
 * <p>Authority boundary (decision D-13): {@code loop_runtime} writes a suggested
 * {@code terminal_disposition} (GUARDED_COMPLETE / HUMAN_HANDOFF / ABANDON / VERIFIED_COMPLETE) into
 * its Worker-side decision. That value is never read here and never written to
 * {@code research_run.status}. Only this gate persists a business terminal state.
 *
 * <p>Infrastructure versus evidence (decision D-9 / DR-107): a provider that is not configured or not
 * available is an infrastructure failure; missing evidence is a business outcome. The vocabulary is
 * reused verbatim from the Worker's {@code loop_stop_guard.INFRASTRUCTURE_REASON_CODES}
 * ({@code PROVIDER_NOT_CONFIGURED} / {@code PROVIDER_UNAVAILABLE}) so the two sides cannot drift.
 *
 * <p>{@code research_run.status} stays inside the existing RUNNING/COMPLETED/FAILED/CANCELLED
 * vocabulary: every business completion maps to COMPLETED and every infrastructure failure maps to
 * FAILED, while {@code completion_terminal_state} keeps the four outcomes distinguishable. Keeping the
 * transport status unchanged is deliberate — the terminal set
 * {@code {"COMPLETED","FAILED","CANCELLED"}} is repeated in eight guards across the research package,
 * so introducing a fifth value would silently un-terminalize the Run.
 *
 * <p>Decision order (fixed, deterministic):
 *
 * <ol>
 *   <li>infrastructure failure with zero promotable cells -&gt; {@code INFRASTRUCTURE_FAILURE};</li>
 *   <li>every cell resolved -&gt; {@code COMPLETED_VERIFIED};</li>
 *   <li>all required cells resolved (and at least one cell promoted) -&gt; {@code COMPLETED_WITH_LIMITATIONS};</li>
 *   <li>otherwise -&gt; {@code INSUFFICIENT_EVIDENCE}. This branch is only reachable from the terminal
 *       barrier, where no task is active and no runnable work exists, so the remaining budget cannot be
 *       spent on more evidence.</li>
 * </ol>
 */
@Service
public class ResearchAgentRunCompletionGate {

    /** A cell is "resolved" only when it is VERIFIED and carries at least one evidence reference. */
    public static final String RESOLVED_CELL_STATUS = "VERIFIED";

    /**
     * DR-303: a cell whose cross-source evidence is mutually exclusive. It is never "resolved", so it
     * can never be promoted, and it must be distinguishable from a cell that simply never received
     * evidence.
     */
    public static final String CONFLICTED_CELL_STATUS = "CONFLICTED";
    /**
     * DR-304: a conflict whose bounded counterfactual repair did not resolve it. It is still a
     * conflict (never resolved, never promoted) but the repair budget is spent, so it must be
     * reported as a distinct, explicit limitation instead of staying forever indistinguishable from
     * an open conflict.
     */
    public static final String CONFLICT_EXHAUSTED_CELL_STATUS = "CONFLICT_EXHAUSTED";
    /**
     * DR-303 conflict-specific reason code (D-27). A conflict is a form of "no trustworthy
     * conclusion", so it keeps the {@code INSUFFICIENT_EVIDENCE} terminal state, but the reason must
     * never be flattened into the same bucket as {@code EVIDENCE_MISSING} /
     * {@code REQUIRED_CELL_UNRESOLVED}. This code is Java-only: it is not consumed by the Worker's
     * loop stop guard vocabulary.
     */
    public static final String REASON_CONFLICTED_CELL = "CONFLICTED_CELL";
    /** Public aggregate reason used by reports/evaluations for any preserved cross-source conflict. */
    public static final String REASON_EVIDENCE_CONFLICT = "EVIDENCE_CONFLICT";
    /**
     * DR-304 stable reason code: the conflict survived the bounded counterfactual repair budget. It is
     * Java-only (not part of the Worker's loop stop guard vocabulary).
     */
    public static final String REASON_CONFLICT_REPAIR_EXHAUSTED = "CONFLICT_REPAIR_EXHAUSTED";

    /** Stable reason codes for a verified, complete ledger. */
    public static final String REASON_ALL_REQUIRED_CELLS_VERIFIED = "ALL_REQUIRED_CELLS_VERIFIED";
    /** Stable reason code for an honest partial completion. */
    public static final String REASON_UNRESOLVED_CELLS_REMAIN = "UNRESOLVED_CELLS_REMAIN";
    /** Stable reason code for a business "no acceptable evidence" outcome. */
    public static final String REASON_INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";
    /** Stable reason code for exhausted hard budget. */
    public static final String REASON_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
    /** Stable reason code recorded when an infrastructure failure left nothing to promote. */
    public static final String REASON_NO_PROMOTABLE_EVIDENCE = "NO_PROMOTABLE_EVIDENCE";
    public static final String REASON_NO_VALID_QUOTE = "NO_VALID_QUOTE";
    /** Stable reason code for a recorded (non-gating) local/global verifier disagreement. */
    public static final String REASON_VERIFIER_DISAGREEMENT = "VERIFIER_DISAGREEMENT";

    public static final String PROVIDER_NOT_CONFIGURED = "PROVIDER_NOT_CONFIGURED";
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    public static final String PROVIDER_FAILED = "PROVIDER_FAILED";

    /** Reused verbatim from the Worker's loop stop guard; never widened here. */
    public static final Set<String> INFRASTRUCTURE_REASON_CODES = Set.of(
            PROVIDER_NOT_CONFIGURED, PROVIDER_UNAVAILABLE, PROVIDER_FAILED);
    /** Hard-budget stop reasons produced by the Worker's loop stop guard. */
    public static final Set<String> RESOURCE_REASON_CODES = Set.of(
            "WALL_CLOCK_BUDGET_EXHAUSTED", "LOOP_BUDGET_EXHAUSTED",
            "CELL_ATTEMPT_LIMIT_REACHED", "REPEATED_CELL_FAILURE");

    /**
     * Persisted extraction / execution signals that really mean "infrastructure", normalized onto the
     * two-code Worker vocabulary. Mirrors {@code loop_stop_guard._INFRASTRUCTURE_CALL_TERMINATIONS}
     * plus the delivery failure code owned by {@link ResearchAgentLifecycleService}.
     */
    private static final Map<String, String> INFRASTRUCTURE_SIGNALS = Map.ofEntries(
            Map.entry("PROVIDER_NOT_CONFIGURED", PROVIDER_NOT_CONFIGURED),
            Map.entry("LLM_UNAVAILABLE", PROVIDER_NOT_CONFIGURED),
            Map.entry("PROVIDER_UNAVAILABLE", PROVIDER_UNAVAILABLE),
            Map.entry("RESEARCH_WEB_PROVIDER_UNAVAILABLE", PROVIDER_UNAVAILABLE),
            Map.entry("RETRY_EXHAUSTED", PROVIDER_UNAVAILABLE),
            Map.entry("EMPTY_CHOICES", PROVIDER_UNAVAILABLE),
            Map.entry("EMPTY_MESSAGE", PROVIDER_UNAVAILABLE),
            Map.entry("INVALID_JSON", PROVIDER_FAILED));
    private static final String NON_RETRYABLE_HTTP_PREFIX = "NON_RETRYABLE_HTTP_";

    /**
     * Matrix columns that describe the limits of an answer rather than the answer itself. A cell in one
     * of these columns may stay unresolved while the Run still completes honestly with limitations.
     */
    private static final Set<String> LIMITATION_COLUMN_KEYS = Set.of("limitations", "implications");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchAgentRunCompletionGate(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ pure decision

    /** Canonical table state: every cell of the Run plus the required column keys of its plan. */
    public record TableState(String runId, List<CellState> cells, Set<String> requiredColumnKeys) {
        public TableState {
            cells = cells == null ? List.of() : List.copyOf(cells);
            // A null required set means "every cell is required" (conservative fallback).
            requiredColumnKeys = requiredColumnKeys == null ? null : Set.copyOf(requiredColumnKeys);
        }

        public boolean isRequired(CellState cell) {
            return requiredColumnKeys == null || requiredColumnKeys.contains(cell.columnKey());
        }
    }

    public record CellState(String cellKey, String columnKey, String status, String candidateValue,
                            List<String> evidenceKeys) {
        public CellState {
            evidenceKeys = evidenceKeys == null ? List.of() : List.copyOf(evidenceKeys);
        }

        public boolean resolved() {
            return RESOLVED_CELL_STATUS.equals(status) && !evidenceKeys.isEmpty();
        }
    }

    /** Verifier signals. Disagreement is recorded as a limitation; it never gates promotion (D-15). */
    public record VerifierState(List<CellVerdict> verdicts) {
        public VerifierState {
            verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        }

        public boolean disagreement() {
            return verdicts.stream().anyMatch(CellVerdict::disagreement);
        }
    }

    public record CellVerdict(String cellKey, String decision, String reasonCode) {
        public boolean disagreement() {
            return mentionsDisagreement(decision) || mentionsDisagreement(reasonCode);
        }

        private static boolean mentionsDisagreement(String value) {
            return value != null && value.toUpperCase(Locale.ROOT).contains("DISAGREEMENT");
        }
    }

    /** Hard-budget state; {@code reasonCodes} are the observed Worker stop reasons. */
    public record BudgetState(boolean exhausted, List<String> reasonCodes) {
        public BudgetState {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    /** Infrastructure state; {@code reasonCode} must be one of {@link #INFRASTRUCTURE_REASON_CODES}. */
    public record InfraState(boolean failed, String reasonCode) {
        public InfraState {
            reasonCode = failed ? normalizeInfrastructureReason(reasonCode) : "";
        }
    }

    public enum CompletionTerminalState {
        COMPLETED_VERIFIED,
        COMPLETED_WITH_LIMITATIONS,
        INSUFFICIENT_EVIDENCE,
        INFRASTRUCTURE_FAILURE;

        public boolean isInfrastructureFailure() {
            return this == INFRASTRUCTURE_FAILURE;
        }

        public boolean isBusinessCompletion() {
            return this != INFRASTRUCTURE_FAILURE;
        }

        /**
         * The single authority mapping from a business terminal state onto the transport
         * {@code research_run.status} vocabulary. Every business outcome — including
         * {@code INSUFFICIENT_EVIDENCE} — maps to COMPLETED so a Run that honestly ran out of
         * evidence is no longer flattened into the same FAILED bucket as an infrastructure fault.
         * Only a real infrastructure failure maps to FAILED.
         */
        public String researchRunStatus() {
            return isInfrastructureFailure() ? "FAILED" : "COMPLETED";
        }
    }

    public record PromotableClaim(String cellKey, String columnKey, String value, List<String> evidenceKeys) {
        public PromotableClaim {
            evidenceKeys = evidenceKeys == null ? List.of() : List.copyOf(evidenceKeys);
        }
    }

    public record UnresolvedCell(String cellKey, String columnKey, String status, String reasonCode) { }

    public record CompletionDecision(CompletionTerminalState terminalState,
                                     List<PromotableClaim> promotableClaims,
                                     List<UnresolvedCell> unresolvedCells,
                                     List<String> limitations,
                                     List<String> reasonCodes) {
        public CompletionDecision {
            Objects.requireNonNull(terminalState, "terminalState");
            promotableClaims = promotableClaims == null ? List.of() : List.copyOf(promotableClaims);
            unresolvedCells = unresolvedCells == null ? List.of() : List.copyOf(unresolvedCells);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    public CompletionDecision evaluate(TableState tableState, VerifierState verifierState,
                                       BudgetState budgetState, InfraState infraState) {
        Objects.requireNonNull(tableState, "tableState");
        List<CellState> cells = tableState.cells();

        List<CellState> resolved = cells.stream().filter(CellState::resolved)
                .sorted(java.util.Comparator.comparing(CellState::cellKey)).toList();
        List<UnresolvedCell> unresolvedCells = cells.stream().filter(cell -> !cell.resolved())
                .sorted(java.util.Comparator.comparing(CellState::cellKey))
                .map(cell -> new UnresolvedCell(cell.cellKey(), cell.columnKey(), cell.status(),
                        remainingReason(tableState, cell)))
                .toList();

        // DR-303: a conflict is a distinct cause, never the same bucket as "evidence was missing".
        // DR-304: a spent conflict-repair budget is itself a distinct, explicit limitation.
        boolean conflict = cells.stream().anyMatch(cell -> isConflictStatus(cell.status()));
        boolean conflictExhausted = cells.stream()
                .anyMatch(cell -> CONFLICT_EXHAUSTED_CELL_STATUS.equals(cell.status()));

        List<String> limitations = new ArrayList<>();
        if (verifierState != null && verifierState.disagreement()) {
            limitations.add(REASON_VERIFIER_DISAGREEMENT);
        }
        if (conflict) {
            limitations.add(REASON_EVIDENCE_CONFLICT);
            limitations.add(REASON_CONFLICTED_CELL);
        }
        if (conflictExhausted) {
            limitations.add(REASON_CONFLICT_REPAIR_EXHAUSTED);
        }
        List<PromotableClaim> promotable = resolved.stream()
                .map(cell -> new PromotableClaim(cell.cellKey(), cell.columnKey(), cell.candidateValue(),
                        cell.evidenceKeys()))
                .toList();

        // 1. Infrastructure failure with nothing promoted: the Provider, not the evidence, is the cause.
        boolean infrastructure = infraState != null && infraState.failed();
        if (infrastructure && promotable.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            reasons.add(infraState.reasonCode());
            reasons.add(REASON_NO_PROMOTABLE_EVIDENCE);
            if (conflict) {
                reasons.add(REASON_EVIDENCE_CONFLICT);
                reasons.add(REASON_CONFLICTED_CELL);
            }
            if (conflictExhausted) reasons.add(REASON_CONFLICT_REPAIR_EXHAUSTED);
            reasons.addAll(budgetReasonCodes(budgetState));
            return new CompletionDecision(CompletionTerminalState.INFRASTRUCTURE_FAILURE, List.of(),
                    unresolvedCells, limitations, dedupe(reasons));
        }

        // 2. Everything resolved: the fully verified terminal state. A later infrastructure fault must
        // stay visible in the reason codes (D-9) without downgrading the strongest true proposition, so
        // the terminal state deliberately stays COMPLETED_VERIFIED.
        if (!cells.isEmpty() && unresolvedCells.isEmpty()) {
            List<String> verifiedReasons = new ArrayList<>();
            verifiedReasons.add(REASON_ALL_REQUIRED_CELLS_VERIFIED);
            if (infrastructure) verifiedReasons.add(infraState.reasonCode());
            return new CompletionDecision(CompletionTerminalState.COMPLETED_VERIFIED, promotable, List.of(),
                    limitations, dedupe(verifiedReasons));
        }

        // 3. Required cells resolved and at least one claim promoted: honest partial completion.
        boolean requiredUnresolved = unresolvedCells.stream()
                .anyMatch(cell -> requiredColumnKey(tableState, cell.columnKey()));
        if (!promotable.isEmpty() && !requiredUnresolved) {
            List<String> partialLimitations = new ArrayList<>(limitations);
            partialLimitations.add(REASON_UNRESOLVED_CELLS_REMAIN);
            List<String> partialReasons = new ArrayList<>();
            partialReasons.add(REASON_UNRESOLVED_CELLS_REMAIN);
            if (conflict) {
                partialReasons.add(REASON_EVIDENCE_CONFLICT);
                partialReasons.add(REASON_CONFLICTED_CELL);
            }
            if (conflictExhausted) partialReasons.add(REASON_CONFLICT_REPAIR_EXHAUSTED);
            // D-9: an infrastructure failure that still left promotable claims must not be hidden.
            // The outcome stays a business completion, but the infra root cause is recorded so the
            // two causes never collapse into one indistinguishable bucket.
            if (infrastructure) partialReasons.add(infraState.reasonCode());
            return new CompletionDecision(CompletionTerminalState.COMPLETED_WITH_LIMITATIONS, promotable,
                    unresolvedCells, dedupe(partialLimitations), dedupe(partialReasons));
        }

        // 4. No acceptable evidence: a business outcome, never FAILED and never COMPLETED_*.
        // DR-303 (D-27): a cell that ended in an explicit conflict keeps this honest business terminal
        // state but carries CONFLICTED_CELL so it is never indistinguishable from missing evidence.
        List<String> reasons = new ArrayList<>();
        reasons.add(REASON_INSUFFICIENT_EVIDENCE);
        reasons.addAll(budgetReasonCodes(budgetState));
        if (conflict) {
            reasons.add(REASON_EVIDENCE_CONFLICT);
            reasons.add(REASON_CONFLICTED_CELL);
        }
        if (conflictExhausted) reasons.add(REASON_CONFLICT_REPAIR_EXHAUSTED);
        if (infrastructure) reasons.add(infraState.reasonCode());
        return new CompletionDecision(CompletionTerminalState.INSUFFICIENT_EVIDENCE, List.of(),
                unresolvedCells, limitations, dedupe(reasons));
    }

    private boolean requiredColumnKey(TableState tableState, String columnKey) {
        return tableState.requiredColumnKeys() == null || tableState.requiredColumnKeys().contains(columnKey);
    }

    private String remainingReason(TableState tableState, CellState cell) {
        // DR-303 (D-27): a conflict is its own reason. It must never be reported as
        // EVIDENCE_MISSING / REQUIRED_CELL_UNRESOLVED / OPTIONAL_CELL_UNRESOLVED.
        // DR-304: an exhausted conflict is a further distinct reason, so "the bounded repair was
        // spent and it is still unresolved" never collapses into an open conflict.
        if (CONFLICT_EXHAUSTED_CELL_STATUS.equals(cell.status())) return REASON_CONFLICT_REPAIR_EXHAUSTED;
        if (CONFLICTED_CELL_STATUS.equals(cell.status())) return REASON_CONFLICTED_CELL;
        if (RESOLVED_CELL_STATUS.equals(cell.status())) return "EVIDENCE_MISSING";
        return requiredColumnKey(tableState, cell.columnKey()) ? "REQUIRED_CELL_UNRESOLVED" : "OPTIONAL_CELL_UNRESOLVED";
    }

    private static boolean isConflictStatus(String status) {
        return CONFLICTED_CELL_STATUS.equals(status) || CONFLICT_EXHAUSTED_CELL_STATUS.equals(status);
    }

    private List<String> budgetReasonCodes(BudgetState budgetState) {
        if (budgetState == null || !budgetState.exhausted()) return List.of();
        List<String> codes = new ArrayList<>();
        for (String code : budgetState.reasonCodes()) {
            if (code != null && RESOURCE_REASON_CODES.contains(code.trim().toUpperCase(Locale.ROOT))) {
                codes.add(code.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (codes.isEmpty()) codes.add(REASON_BUDGET_EXHAUSTED);
        return codes;
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    static String normalizeInfrastructureReason(String signal) {
        String normalized = signal == null ? "" : signal.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return PROVIDER_UNAVAILABLE;
        String mapped = INFRASTRUCTURE_SIGNALS.get(normalized);
        if (mapped != null) return mapped;
        if (normalized.startsWith(NON_RETRYABLE_HTTP_PREFIX)) return PROVIDER_UNAVAILABLE;
        return normalized;
    }

    // ------------------------------------------------------------------ persisted state readers

    /** Reads the already-persisted canonical state for a Run and returns the terminal decision. */
    public CompletionDecision evaluateForRun(String runId) {
        CompletionDecision decision = evaluate(
                loadTableState(runId), loadVerifierState(runId), loadBudgetState(runId), loadInfraState(runId));
        if (!hasNoValidQuoteSignal(runId)) return decision;
        List<String> reasons = new ArrayList<>(decision.reasonCodes());
        reasons.add(REASON_NO_VALID_QUOTE);
        return new CompletionDecision(decision.terminalState(), decision.promotableClaims(),
                decision.unresolvedCells(), decision.limitations(), dedupe(reasons));
    }

    private boolean hasNoValidQuoteSignal(String runId) {
        for (String diagnostics : jdbcTemplate.queryForList("""
                select execution.extraction_diagnostics_json
                from research_agent_execution execution
                join research_agent_task task on task.id = execution.research_agent_task_id
                where task.research_run_id = ? and execution.extraction_diagnostics_json is not null
                """, String.class, runId)) {
            try {
                JsonNode root = objectMapper.readTree(diagnostics);
                int accepted = root.path("accepted_count").asInt(0);
                int rejected = root.path("rejected_count").asInt(0);
                int nonExact = root.path("rejection_counts").path("NON_EXACT_QUOTE").asInt(0);
                if (accepted == 0 && rejected > 0 && nonExact == rejected) return true;
            } catch (Exception ignored) {
                // Malformed advisory diagnostics cannot create a terminal reason.
            }
        }
        return false;
    }

    /**
     * Stable terminal reason for a Run that the gate classified as an infrastructure failure. Prefers
     * the concrete Worker reason code so the persisted trace names the root cause instead of the old
     * opaque {@code RESEARCH_AGENT_TERMINAL_BARRIER_UNSATISFIED}.
     */
    public String infrastructureTerminalReason(CompletionDecision decision) {
        Objects.requireNonNull(decision, "decision");
        if (!decision.terminalState().isInfrastructureFailure()) {
            throw new IllegalArgumentException("Decision is not an infrastructure failure");
        }
        return decision.reasonCodes().stream()
                .filter(INFRASTRUCTURE_REASON_CODES::contains)
                .findFirst()
                .orElseGet(() -> decision.reasonCodes().stream().findFirst()
                        .orElse("RESEARCH_AGENT_INFRASTRUCTURE_FAILURE"));
    }

    /** Worker termination reasons that mean "searched, found no usable evidence" rather than a broken runtime. */
    static final Set<String> EVIDENCE_TERMINATION_REASONS = Set.of("NO_SUPPORTED_CANDIDATE", "EVIDENCE_ONLY");

    /**
     * True when the Run has failed tasks and every one of them ended on an evidence outcome. Such a
     * failed wave with no safe repair target left is a business result (missing evidence), so it must
     * go through the completion decision instead of failing the whole Run; lease exhaustion, delivery
     * failures and other runtime reasons keep failing it.
     */
    public boolean failedTasksAreEvidenceOutcomes(String runId) {
        List<String> reasons = jdbcTemplate.queryForList("""
                select coalesce(terminal_reason, '') from research_agent_task
                where research_run_id = ? and status = 'FAILED'
                """, String.class, runId);
        return !reasons.isEmpty() && EVIDENCE_TERMINATION_REASONS.containsAll(reasons);
    }

    public TableState loadTableState(String runId) {
        List<CellState> cells = jdbcTemplate.query("""
                select cell_key, column_key, cell_status, candidate_value, evidence_refs_json
                from research_cell
                where research_run_id = ?
                order by cast(cell_key as binary), cell_key
                """, (rs, rowNum) -> new CellState(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                readEvidenceKeys(rs.getString(5))), runId);
        return new TableState(runId, cells, loadRequiredColumnKeys(runId));
    }

    /**
     * Required column keys come from the persisted Matrix plan: every declared column except the
     * explicit limitation columns. When no plan was persisted the gate degrades conservatively to
     * "every cell is required" so it can never over-claim a partial answer.
     */
    Set<String> loadRequiredColumnKeys(String runId) {
        String planJson = jdbcTemplate.query("""
                select plan_json from research_matrix_plan
                where research_run_id = ?
                order by created_at desc, id desc limit 1
                """, rs -> rs.next() ? rs.getString(1) : null, runId);
        if (planJson == null || planJson.isBlank()) return null;
        Set<String> required = new TreeSet<>();
        try {
            JsonNode columns = objectMapper.readTree(planJson).path("columns");
            for (JsonNode column : columns) {
                String key = column.path("key").asText("");
                if (!key.isBlank() && !LIMITATION_COLUMN_KEYS.contains(key)) required.add(key);
            }
        } catch (Exception exception) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_PLAN_INVALID",
                    "Persisted research matrix plan is not readable");
        }
        return required.isEmpty() ? null : required;
    }

    public VerifierState loadVerifierState(String runId) {
        List<CellVerdict> verdicts = jdbcTemplate.query("""
                select target_id, decision_type, reason_code
                from research_verifier_decision
                where research_run_id = ?
                order by created_at, id
                """, (rs, rowNum) -> new CellVerdict(rs.getString(1), rs.getString(2), rs.getString(3)), runId);
        return new VerifierState(verdicts);
    }

    public BudgetState loadBudgetState(String runId) {
        List<String> reasons = new ArrayList<>();
        for (String signal : persistedSignals(runId)) {
            String normalized = signal.trim().toUpperCase(Locale.ROOT);
            if (RESOURCE_REASON_CODES.contains(normalized)) reasons.add(normalized);
        }
        return new BudgetState(!reasons.isEmpty(), reasons);
    }

    public InfraState loadInfraState(String runId) {
        Set<String> normalized = new TreeSet<>();
        for (String signal : persistedSignals(runId)) {
            String candidate = signal.trim().toUpperCase(Locale.ROOT);
            if (INFRASTRUCTURE_SIGNALS.containsKey(candidate)
                    || candidate.startsWith(NON_RETRYABLE_HTTP_PREFIX)) {
                normalized.add(normalizeInfrastructureReason(candidate));
            }
        }
        // Deterministic pick: an unconfigured provider is the more specific root cause.
        String reason = normalized.contains(PROVIDER_NOT_CONFIGURED)
                ? PROVIDER_NOT_CONFIGURED
                : normalized.stream().findFirst().orElse("");
        return new InfraState(!normalized.isEmpty(), reason);
    }

    /**
     * Every persisted terminal signal for the Run: task terminal reasons, execution termination reasons
     * and the DR-102 extraction diagnostics termination reason.
     */
    private List<String> persistedSignals(String runId) {
        List<String> signals = new ArrayList<>();
        signals.addAll(jdbcTemplate.queryForList("""
                select terminal_reason from research_agent_task
                where research_run_id = ? and terminal_reason is not null and terminal_reason <> ''
                """, String.class, runId));
        signals.addAll(jdbcTemplate.queryForList("""
                select execution.termination_reason
                from research_agent_execution execution
                join research_agent_task task on task.id = execution.research_agent_task_id
                where task.research_run_id = ?
                  and execution.termination_reason is not null and execution.termination_reason <> ''
                """, String.class, runId));
        for (String diagnostics : jdbcTemplate.queryForList("""
                select execution.extraction_diagnostics_json
                from research_agent_execution execution
                join research_agent_task task on task.id = execution.research_agent_task_id
                where task.research_run_id = ? and execution.extraction_diagnostics_json is not null
                """, String.class, runId)) {
            String termination = diagnosticsTerminationReason(diagnostics);
            if (!termination.isBlank()) signals.add(termination);
        }
        return signals;
    }

    private String diagnosticsTerminationReason(String diagnosticsJson) {
        if (diagnosticsJson == null || diagnosticsJson.isBlank()) return "";
        try {
            return objectMapper.readTree(diagnosticsJson).path("termination_reason").asText("");
        } catch (Exception exception) {
            // Diagnostics are advisory input; an unreadable blob must not fail the gate.
            return "";
        }
    }

    private List<String> readEvidenceKeys(String evidenceRefsJson) {
        if (evidenceRefsJson == null || evidenceRefsJson.isBlank()) return List.of();
        try {
            JsonNode node = objectMapper.readTree(evidenceRefsJson);
            if (!node.isArray()) return List.of();
            List<String> keys = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("");
                if (!value.isBlank()) keys.add(value);
            }
            return keys;
        } catch (Exception exception) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ persistence

    /** Persists the decision next to the Run; the four outcomes stay distinguishable and queryable. */
    @Transactional
    public void recordDecision(String runId, CompletionDecision decision) {
        Objects.requireNonNull(decision, "decision");
        int updated = jdbcTemplate.update("""
                update research_run
                set completion_terminal_state = ?,
                    completion_reason_codes_json = ?,
                    completion_unresolved_cells_json = ?,
                    completion_limitations_json = ?,
                    completion_promotable_claims_json = ?,
                    updated_at = current_timestamp
                where id = ?
                """, decision.terminalState().name(), Json.write(objectMapper, decision.reasonCodes()),
                Json.write(objectMapper, decision.unresolvedCells()), Json.write(objectMapper, decision.limitations()),
                Json.write(objectMapper, decision.promotableClaims()), runId);
        if (updated != 1) {
            throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Research run does not exist");
        }
    }
}
