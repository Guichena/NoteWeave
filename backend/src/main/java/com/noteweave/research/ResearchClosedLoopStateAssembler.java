package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.castMapOrEmpty;
import static com.noteweave.research.ResearchReadModelMapper.extractListOfMaps;
import static com.noteweave.research.ResearchReadModelMapper.readCounterfactualSummary;
import static com.noteweave.research.ResearchReadModelMapper.readRecoveryTargetsResponse;
import static com.noteweave.research.ResearchReadModelMapper.readStateLedgerResponse;
import static com.noteweave.research.ResearchReadModelMapper.stringValue;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Assembles the closed-loop response from trace payloads and persisted projections. */
@Component
public class ResearchClosedLoopStateAssembler {

    private final ResearchEvidenceSampleAssembler evidenceSampleAssembler;
    private final ResearchCheckpointReadModelAssembler checkpointReadModelAssembler;
    private final ResearchCounterfactualSummaryAssembler counterfactualSummaryAssembler;

    public ResearchClosedLoopStateAssembler(
            ResearchEvidenceSampleAssembler evidenceSampleAssembler,
            ResearchCheckpointReadModelAssembler checkpointReadModelAssembler,
            ResearchCounterfactualSummaryAssembler counterfactualSummaryAssembler
    ) {
        this.evidenceSampleAssembler = evidenceSampleAssembler;
        this.checkpointReadModelAssembler = checkpointReadModelAssembler;
        this.counterfactualSummaryAssembler = counterfactualSummaryAssembler;
    }

    public ResearchClosedLoopStateResponse build(
            List<ResearchTraceResponse> traces,
            ResearchClosedLoopData data,
            Map<String, Object> recoveryTargets
    ) {
        Map<String, Object> harnessSummary = extractNestedMap(traces, "HARNESS_SUMMARY", "harness_summary");
        Map<String, Object> checkpointCandidate = extractNestedMap(
                traces, "RESEARCH_CHECKPOINT", "research_checkpoint_candidate");
        Map<String, Object> finalResultPayload = extractNestedMap(traces, "FINAL_REPORT", "result_payload");
        Map<String, Object> harnessControlState = extractStructuredResultMap(
                traces,
                "HARNESS_CONTROL_STATE",
                "harness_control_state",
                finalResultPayload.get("harness_control_state"),
                checkpointCandidate.get("harness_control_state")
        );
        Map<String, Object> auditSummaries = extractStructuredResultMap(
                traces,
                "AUDIT_SUMMARIES",
                "audit_summaries",
                harnessSummary.get("audit_summaries"),
                finalResultPayload.get("audit_summaries"),
                checkpointCandidate.get("audit_summaries")
        );
        Map<String, Object> toolboxSummary = extractStructuredResultMap(
                traces,
                "TOOLBOX_SUMMARY",
                "toolbox_summary",
                harnessSummary.get("toolbox_summary"),
                finalResultPayload.get("toolbox_summary"),
                checkpointCandidate.get("toolbox_summary")
        );
        Map<String, Object> localVerifier = extractNestedMap(traces, "LOCAL_VERIFIER", "local_verifier");
        Map<String, Object> globalVerifier = extractNestedMap(traces, "GLOBAL_VERIFIER", "global_verifier");
        ResearchCounterfactualSummaryResponse counterfactualSummary = readCounterfactualSummary(
                extractNestedMap(traces, "COUNTERFACTUAL_SUMMARY", "counterfactual_summary")
        );
        List<Map<String, Object>> persistedBranches = data.branches();
        List<Map<String, Object>> persistedRows = data.rows();
        List<Map<String, Object>> persistedCells = data.cells();
        List<Map<String, Object>> persistedSourceEvidence = data.sourceEvidence();
        List<Map<String, Object>> persistedVerifierDecisions = data.verifierDecisions();
        List<Map<String, Object>> persistedCheckpoints = data.checkpoints();
        List<Map<String, Object>> persistedCellEvidence = data.cellEvidence();
        List<Map<String, Object>> branchDecisions = extractNestedListOfMaps(traces, "BRANCH_DECISIONS", "branch_decisions");
        Map<String, Object> loopRuntime = extractTracePayload(traces, "LOOP_RUNTIME");
        List<Map<String, Object>> loopRounds = extractListOfMaps(loopRuntime.get("loop_rounds"));
        evidenceSampleAssembler.enrichLoopRoundsWithSourceSamples(loopRounds, persistedSourceEvidence);
        Map<String, Object> loopDecision = castMapOrEmpty(loopRuntime.get("loop_decision"));
        Map<String, Object> stateLedger = mergeStateLedgerSnapshot(
                extractNestedMap(traces, "STATE_LEDGER", "state_ledger"),
                persistedBranches,
                persistedRows,
                persistedCells,
                persistedVerifierDecisions
        );
        ResearchStateLedgerResponse stateLedgerResponse = readStateLedgerResponse(stateLedger);
        List<Map<String, Object>> effectiveRows = stateLedgerResponse.rows();
        List<ResearchCheckpointSummaryResponse> checkpointResponses = persistedCheckpoints.stream()
                .map(checkpointReadModelAssembler::toSummary)
                .toList();
        if (counterfactualSummary == null) {
            counterfactualSummary = counterfactualSummaryAssembler.build(
                    Map.of(),
                    List.of(),
                    branchDecisions,
                    persistedBranches,
                    effectiveRows,
                    stringValue(stateLedger.get("active_branch_id")),
                    stringValue(localVerifier.get("status")),
                    stringValue(globalVerifier.get("decision")),
                    ""
            );
        }

        return new ResearchClosedLoopStateResponse(
                stringValue(stateLedger.get("active_branch_id")),
                stringValue(localVerifier.get("status")),
                stringValue(globalVerifier.get("decision")),
                stringValue(loopDecision.get("decision")),
                loopRounds.size(),
                persistedRows.size(),
                persistedBranches.size(),
                persistedVerifierDecisions.size(),
                harnessSummary,
                checkpointCandidate,
                harnessControlState,
                auditSummaries,
                toolboxSummary,
                counterfactualSummary,
                readRecoveryTargetsResponse(recoveryTargets),
                stateLedgerResponse,
                localVerifier,
                globalVerifier,
                persistedBranches,
                effectiveRows,
                persistedCells,
                persistedVerifierDecisions,
                checkpointResponses,
                persistedSourceEvidence,
                persistedCellEvidence,
                branchDecisions,
                loopRounds,
                loopDecision
        );
    }

    private Map<String, Object> extractTracePayload(List<ResearchTraceResponse> traces, String traceType) {
        return traces.stream()
                .filter(trace -> traceType.equals(trace.traceType()))
                .reduce((first, second) -> second)
                .map(ResearchTraceResponse::payload)
                .orElse(Map.of());
    }

    private Map<String, Object> extractNestedMap(List<ResearchTraceResponse> traces, String traceType, String key) {
        Map<String, Object> payload = extractTracePayload(traces, traceType);
        return castMapOrEmpty(payload.get(key));
    }

    private Map<String, Object> extractStructuredResultMap(
            List<ResearchTraceResponse> traces,
            String traceType,
            String key,
            Object... fallbackCandidates
    ) {
        Map<String, Object> direct = extractNestedMap(traces, traceType, key);
        if (!direct.isEmpty()) {
            return direct;
        }
        for (Object candidate : fallbackCandidates) {
            Map<String, Object> fallback = castMapOrEmpty(candidate);
            if (!fallback.isEmpty()) {
                return fallback;
            }
        }
        return Map.of();
    }

    private List<Map<String, Object>> extractNestedListOfMaps(
            List<ResearchTraceResponse> traces,
            String traceType,
            String key
    ) {
        Map<String, Object> payload = extractTracePayload(traces, traceType);
        return extractListOfMaps(payload.get(key));
    }

    private Map<String, Object> mergeStateLedgerSnapshot(
            Map<String, Object> stateLedger,
            List<Map<String, Object>> persistedBranches,
            List<Map<String, Object>> persistedRows,
            List<Map<String, Object>> persistedCells,
            List<Map<String, Object>> persistedVerifierDecisions
    ) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(stateLedger);
        List<Map<String, Object>> effectiveRows = deriveVerifiedRowsFromCells(persistedRows, persistedCells);
        merged.put("branches", persistedBranches);
        merged.put("rows", effectiveRows);
        merged.put("cells", persistedCells);
        merged.put("verifier_decisions", persistedVerifierDecisions);
        if (!persistedBranches.isEmpty() && stringValue(merged.get("active_branch_id")).isBlank()) {
            merged.put("active_branch_id", stringValue(persistedBranches.get(persistedBranches.size() - 1).get("branch_id")));
        }
        merged.put("verified_row_count", effectiveRows.stream()
                .filter(row -> "VERIFIED".equals(stringValue(row.get("row_status"))))
                .count());
        merged.put("conflicted_row_count", effectiveRows.stream()
                .filter(row -> "CONFLICTED".equals(stringValue(row.get("row_status"))))
                .count());
        return merged;
    }

    /**
     * Incremental agent runs verify canonical cells directly. Older row projections can therefore
     * remain DISCOVERED even after every child cell has passed the merge gate. The read model must
     * expose the effective verified state or completed runs look unfinished in the audit UI.
     */
    private List<Map<String, Object>> deriveVerifiedRowsFromCells(
            List<Map<String, Object>> rows,
            List<Map<String, Object>> cells
    ) {
        List<Map<String, Object>> effective = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String rowId = stringValue(row.get("row_id"));
            List<Map<String, Object>> rowCells = cells.stream()
                    .filter(cell -> rowId.equals(stringValue(cell.get("row_id"))))
                    .toList();
            boolean fullyVerified = !rowCells.isEmpty() && rowCells.stream()
                    .allMatch(cell -> "VERIFIED".equals(stringValue(cell.get("status"))));
            if (fullyVerified && !"CONFLICTED".equals(stringValue(row.get("row_status")))) {
                LinkedHashMap<String, Object> derived = new LinkedHashMap<>(row);
                derived.put("row_status", "VERIFIED");
                if (stringValue(derived.get("verification_status")).isBlank()) {
                    derived.put("verification_status", "CELL_LEDGER_VERIFIED");
                }
                effective.add(derived);
            } else {
                effective.add(row);
            }
        }
        return List.copyOf(effective);
    }
}
