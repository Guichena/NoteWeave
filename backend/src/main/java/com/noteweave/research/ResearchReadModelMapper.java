package com.noteweave.research;

import java.util.List;
import java.util.Map;

/**
 * Maps persisted Research payloads into the stable read-model contract.
 *
 * <p>This module deliberately owns the tolerant coercion rules used by legacy
 * checkpoint payloads so query orchestration does not need to understand JSON
 * representation details.</p>
 */
final class ResearchReadModelMapper {

    private ResearchReadModelMapper() {
    }

    static ResearchCheckpointSnapshotSummaryResponse readCheckpointSnapshotSummaryResponse(
            Map<String, Object> summary
    ) {
        Map<String, Object> safeSummary = castMapOrEmpty(summary);
        return new ResearchCheckpointSnapshotSummaryResponse(
                intValue(safeSummary.get("checkpoint_no")),
                blankIfNull(stringValue(safeSummary.get("snapshot_type"))),
                nonEmptyMapOrNull(safeSummary.get("loop_decision")),
                nonEmptyMapOrNull(safeSummary.get("local_verifier")),
                nonEmptyMapOrNull(safeSummary.get("global_verifier")),
                readCheckpointStateLedgerSummaryResponse(castMapOrEmpty(safeSummary.get("state_ledger"))),
                nonEmptyMapOrNull(safeSummary.get("research_intent_alignment")),
                blankIfNull(stringValue(safeSummary.get("research_intent_alignment_status"))),
                blankIfNull(stringValue(safeSummary.get("research_intent_alignment_reason"))),
                intValue(safeSummary.get("intent_constraint_count")),
                intValue(safeSummary.get("intent_satisfied_constraint_count")),
                nonEmptyMapOrNull(safeSummary.get("intent_completion_contract")),
                intValue(safeSummary.get("intent_requirement_count")),
                intValue(safeSummary.get("intent_satisfied_requirement_count")),
                intValue(safeSummary.get("intent_pending_requirement_count")),
                extractStringList(safeSummary.get("missing_intent_requirements")),
                readCounterfactualSummary(castMapOrEmpty(safeSummary.get("counterfactual_summary"))),
                readRecoveryTargetsResponse(castMapOrEmpty(safeSummary.get("recovery_targets"))),
                readVerifierGatedSummaryResponse(nonEmptyMapOrNull(safeSummary.get("verifier_gated_summary")))
        );
    }

    static ResearchCheckpointStateLedgerSummaryResponse readCheckpointStateLedgerSummaryResponse(
            Map<String, Object> stateLedger
    ) {
        Map<String, Object> safeLedger = castMapOrEmpty(stateLedger);
        return new ResearchCheckpointStateLedgerSummaryResponse(
                blankIfNull(stringValue(safeLedger.get("active_branch_id"))),
                intValue(safeLedger.get("row_count")),
                intValue(safeLedger.get("column_count")),
                intValue(safeLedger.get("branch_count")),
                intValue(safeLedger.get("cell_count")),
                intValue(safeLedger.get("verified_row_count")),
                intValue(safeLedger.get("conflicted_row_count")),
                intValue(safeLedger.get("requirement_ready_row_count")),
                intValue(safeLedger.get("requirement_partial_row_count")),
                intValue(safeLedger.get("read_window_count")),
                intValue(safeLedger.get("evidence_card_count")),
                extractListOfMaps(safeLedger.get("verified_row_samples")),
                extractListOfMaps(safeLedger.get("conflicted_row_samples")),
                extractListOfMaps(safeLedger.get("evidence_card_samples")),
                extractListOfMaps(safeLedger.get("read_window_samples")),
                intValue(safeLedger.get("blocked_row_count")),
                intValue(safeLedger.get("guardrailed_row_count")),
                intValue(safeLedger.get("recovery_targeted_blocked_row_count")),
                intValue(safeLedger.get("uncovered_blocked_row_count")),
                intValue(safeLedger.get("requirement_partial_blocked_row_count")),
                extractListOfMaps(safeLedger.get("blocked_row_samples")),
                extractListOfMaps(safeLedger.get("guardrailed_row_samples")),
                extractListOfMaps(safeLedger.get("need_more_evidence_row_samples")),
                nonEmptyMapOrNull(safeLedger.get("intent_completion_contract")),
                intValue(safeLedger.get("intent_requirement_count")),
                intValue(safeLedger.get("intent_satisfied_requirement_count")),
                intValue(safeLedger.get("intent_pending_requirement_count")),
                extractStringList(safeLedger.get("missing_intent_requirements"))
        );
    }

    static ResearchStateLedgerResponse readStateLedgerResponse(Map<String, Object> stateLedger) {
        Map<String, Object> safeLedger = castMapOrEmpty(stateLedger);
        return new ResearchStateLedgerResponse(
                blankIfNull(stringValue(safeLedger.get("active_branch_id"))),
                extractListOfMaps(safeLedger.get("columns")),
                extractListOfMaps(safeLedger.get("branch_sessions")),
                extractListOfMaps(safeLedger.get("branches")),
                extractListOfMaps(safeLedger.get("rows")),
                extractListOfMaps(safeLedger.get("cells")),
                extractListOfMaps(safeLedger.get("verifier_decisions")),
                extractListOfMaps(safeLedger.get("required_finding_contract")),
                extractListOfMaps(safeLedger.get("required_finding_progress")),
                intValue(safeLedger.get("verified_row_count")),
                intValue(safeLedger.get("conflicted_row_count")),
                intValue(safeLedger.get("requirement_ready_row_count")),
                intValue(safeLedger.get("requirement_partial_row_count")),
                readRecoveryTargetsResponse(castMapOrEmpty(safeLedger.get("recovery_targets"))),
                nonEmptyMapOrNull(safeLedger.get("intent_completion_contract"))
        );
    }

    static ResearchArtifactCandidateResponse readResearchArtifactCandidateResponse(Map<String, Object> artifact) {
        if (artifact == null || artifact.isEmpty()) {
            return null;
        }
        Map<String, Object> safeArtifact = castMapOrEmpty(artifact);
        return new ResearchArtifactCandidateResponse(
                blankIfNull(stringValue(safeArtifact.get("artifact_type"))),
                blankIfNull(stringValue(safeArtifact.get("artifact_version"))),
                blankIfNull(stringValue(safeArtifact.get("title"))),
                blankIfNull(stringValue(safeArtifact.get("question"))),
                blankIfNull(stringValue(safeArtifact.get("generated_by"))),
                blankIfNull(stringValue(safeArtifact.get("generated_ref_type"))),
                blankIfNull(stringValue(safeArtifact.get("generated_ref_id"))),
                blankIfNull(stringValue(safeArtifact.get("answer_status"))),
                blankIfNull(stringValue(safeArtifact.get("confidence_label"))),
                blankIfNull(stringValue(safeArtifact.get("coverage_label"))),
                blankIfNull(stringValue(safeArtifact.get("source_basis"))),
                blankIfNull(stringValue(safeArtifact.get("answer_text"))),
                blankIfNull(stringValue(safeArtifact.get("content_markdown"))),
                nonEmptyMapOrNull(safeArtifact.get("report_structure")),
                nonEmptyMapOrNull(safeArtifact.get("source_foundation")),
                nonEmptyMapOrNull(safeArtifact.get("research_intent")),
                nonEmptyMapOrNull(safeArtifact.get("closed_loop_state")),
                readResumeContextSummaryResponse(nonEmptyMapOrNull(safeArtifact.get("resume_context_summary"))),
                intValue(safeArtifact.get("citation_count")),
                extractListOfMaps(safeArtifact.get("citations"))
        );
    }

    static ResearchResumeContextSummaryResponse readResumeContextSummaryResponse(Map<String, Object> summary) {
        if (summary == null || summary.isEmpty()) {
            return null;
        }
        Map<String, Object> safeSummary = castMapOrEmpty(summary);
        return new ResearchResumeContextSummaryResponse(
                blankIfNull(stringValue(safeSummary.get("source_research_run_id"))),
                intValue(safeSummary.get("checkpoint_no")),
                blankIfNull(stringValue(safeSummary.get("snapshot_type"))),
                blankIfNull(stringValue(safeSummary.get("active_branch_id"))),
                blankIfNull(stringValue(safeSummary.get("final_loop_decision"))),
                intValue(safeSummary.get("restored_search_hit_count")),
                intValue(safeSummary.get("restored_read_window_count")),
                intValue(safeSummary.get("restored_evidence_card_count")),
                intValue(safeSummary.get("restored_loop_round_count")),
                intValue(safeSummary.get("restored_tool_trace_count"))
        );
    }

    static ResearchCounterfactualSummaryResponse readCounterfactualSummary(Map<String, Object> payload) {
        if (payload.isEmpty()) {
            return null;
        }
        return new ResearchCounterfactualSummaryResponse(
                booleanValue(payload.get("has_counterfactual_recheck")),
                intValue(payload.get("counterfactual_branch_count")),
                intValue(payload.get("conflicted_row_count")),
                blankIfNull(stringValue(payload.get("local_verifier_status"))),
                blankIfNull(stringValue(payload.get("global_verifier_decision"))),
                blankIfNull(stringValue(payload.get("recovery_mode"))),
                extractStringList(payload.get("counterfactual_branch_ids")),
                extractStringList(payload.get("counterfactual_session_ids")),
                extractStringList(payload.get("active_counterfactual_branch_ids")),
                extractStringList(payload.get("active_counterfactual_session_ids")),
                extractStringList(payload.get("branch_reasons")),
                extractStringList(payload.get("target_evidence_ids")),
                extractCounterfactualBranches(payload.get("branches"))
        );
    }

    static ResearchRecoveryTargetsResponse readRecoveryTargetsResponse(Map<String, Object> recoveryTargets) {
        if (recoveryTargets == null || recoveryTargets.isEmpty()) {
            return emptyRecoveryTargetsResponse();
        }
        return new ResearchRecoveryTargetsResponse(
                extractStringList(recoveryTargets.get("requirement_ids")),
                extractStringList(recoveryTargets.get("requirement_types")),
                extractStringList(recoveryTargets.get("requirement_labels")),
                extractStringList(recoveryTargets.get("target_columns")),
                extractStringList(recoveryTargets.get("target_queries")),
                extractStringList(recoveryTargets.get("target_sources")),
                intValue(recoveryTargets.get("requirement_count")),
                intValue(recoveryTargets.get("query_count")),
                intValue(recoveryTargets.get("source_count")),
                intValue(recoveryTargets.get("column_count"))
        );
    }

    static ResearchVerifierGatedSummaryResponse readVerifierGatedSummaryResponse(
            Map<String, Object> verifierGatedSummary
    ) {
        if (verifierGatedSummary == null || verifierGatedSummary.isEmpty()) {
            return emptyVerifierGatedSummaryResponse();
        }
        return new ResearchVerifierGatedSummaryResponse(
                intValue(verifierGatedSummary.get("blocked_row_count")),
                intValue(verifierGatedSummary.get("guardrailed_row_count")),
                intValue(verifierGatedSummary.get("recovery_targeted_blocked_row_count")),
                intValue(verifierGatedSummary.get("uncovered_blocked_row_count")),
                intValue(verifierGatedSummary.get("requirement_partial_blocked_row_count")),
                extractListOfMaps(verifierGatedSummary.get("blocked_row_samples")),
                extractListOfMaps(verifierGatedSummary.get("guardrailed_row_samples")),
                extractListOfMaps(verifierGatedSummary.get("need_more_evidence_row_samples"))
        );
    }

    static List<Map<String, Object>> extractListOfMaps(Object value) {
        if (!(value instanceof List<?> listValue)) {
            return List.of();
        }
        return listValue.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(ResearchReadModelMapper::castMap)
                .toList();
    }

    static Map<String, Object> castMapOrEmpty(Object value) {
        if (!(value instanceof Map<?, ?> mapValue)) {
            return Map.of();
        }
        return castMap(mapValue);
    }

    static Map<String, Object> nonEmptyMapOrNull(Object value) {
        Map<String, Object> mapValue = castMapOrEmpty(value);
        return mapValue.isEmpty() ? null : mapValue;
    }

    static Object firstNonNull(Object first, Object fallback) {
        return first != null ? first : fallback;
    }

    static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    static boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = stringValue(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    static List<String> extractStringList(Object value) {
        if (value instanceof List<?> listValue) {
            return listValue.stream()
                    .map(String::valueOf)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text);
        }
        return List.of();
    }

    static String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static List<ResearchCounterfactualBranchResponse> extractCounterfactualBranches(Object value) {
        return extractListOfMaps(value).stream()
                .map(branch -> new ResearchCounterfactualBranchResponse(
                        blankIfNull(stringValue(branch.get("branch_id"))),
                        blankIfNull(stringValue(branch.get("session_id"))),
                        blankIfNull(stringValue(branch.get("parent_branch_id"))),
                        blankIfNull(stringValue(branch.get("parent_session_id"))),
                        blankIfNull(stringValue(branch.get("branch_reason"))),
                        blankIfNull(stringValue(branch.get("branch_status"))),
                        blankIfNull(stringValue(branch.get("execution_mode"))),
                        extractStringList(branch.get("sibling_branch_ids")),
                        blankIfNull(stringValue(branch.get("decision"))),
                        blankIfNull(stringValue(branch.get("verifier_scope"))),
                        blankIfNull(stringValue(branch.get("hypothesis_summary"))),
                        extractStringList(branch.get("target_evidence_ids"))
                ))
                .toList();
    }

    private static ResearchRecoveryTargetsResponse emptyRecoveryTargetsResponse() {
        return new ResearchRecoveryTargetsResponse(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, 0, 0
        );
    }

    private static ResearchVerifierGatedSummaryResponse emptyVerifierGatedSummaryResponse() {
        return new ResearchVerifierGatedSummaryResponse(0, 0, 0, 0, 0, List.of(), List.of(), List.of());
    }

    private static Map<String, Object> castMap(Map<?, ?> mapValue) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        mapValue.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
