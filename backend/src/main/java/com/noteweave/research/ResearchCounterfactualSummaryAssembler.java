package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.blankIfNull;
import static com.noteweave.research.ResearchReadModelMapper.extractListOfMaps;
import static com.noteweave.research.ResearchReadModelMapper.extractStringList;
import static com.noteweave.research.ResearchReadModelMapper.firstNonNull;
import static com.noteweave.research.ResearchReadModelMapper.stringValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Derives the counterfactual branch summary from persisted and trace read models. */
@Component
public class ResearchCounterfactualSummaryAssembler {

    public ResearchCounterfactualSummaryResponse build(
            Map<String, Object> conflictReview,
            List<Map<String, Object>> reportBranchDecisions,
            List<Map<String, Object>> stateBranchDecisions,
            List<Map<String, Object>> branches,
            List<Map<String, Object>> rows,
            String activeBranchId,
            String localVerifierStatus,
            String globalVerifierDecision,
            String recoveryMode
    ) {
        List<Map<String, Object>> decisionCandidates = !reportBranchDecisions.isEmpty()
                ? reportBranchDecisions
                : stateBranchDecisions;
        LinkedHashMap<String, Map<String, Object>> branchMap = new LinkedHashMap<>();
        for (Map<String, Object> branch : branches) {
            String branchId = stringValue(firstNonNull(branch.get("branch_id"), branch.get("branch_key")));
            if (!branchId.isBlank()) {
                branchMap.put(branchId, branch);
            }
        }

        LinkedHashSet<String> counterfactualBranchIds = new LinkedHashSet<>();
        LinkedHashSet<String> counterfactualSessionIds = new LinkedHashSet<>();
        LinkedHashSet<String> activeCounterfactualBranchIds = new LinkedHashSet<>();
        LinkedHashSet<String> activeCounterfactualSessionIds = new LinkedHashSet<>();
        LinkedHashSet<String> branchReasons = new LinkedHashSet<>();
        LinkedHashSet<String> targetEvidenceIds = new LinkedHashSet<>();
        List<ResearchCounterfactualBranchResponse> counterfactualBranches = new ArrayList<>();

        for (Map<String, Object> decision : decisionCandidates) {
            String decisionType = stringValue(firstNonNull(decision.get("decision"), decision.get("decision_type")));
            if (!"COUNTERFACTUAL_RECHECK".equals(decisionType)) {
                continue;
            }
            String branchId = stringValue(firstNonNull(
                    decision.get("branch_id"),
                    firstNonNull(decision.get("target_id"), decision.get("branch_key"))
            ));
            Map<String, Object> branch = branchId.isBlank()
                    ? Map.of()
                    : branchMap.getOrDefault(branchId, Map.of());
            String sessionId = blankIfNull(stringValue(firstNonNull(decision.get("session_id"), branch.get("session_id"))));
            String parentSessionId = blankIfNull(stringValue(firstNonNull(decision.get("parent_session_id"), branch.get("parent_session_id"))));
            String executionMode = blankIfNull(stringValue(firstNonNull(decision.get("execution_mode"), branch.get("execution_mode"))));
            List<String> siblingBranchIds = extractStringList(firstNonNull(
                    decision.get("sibling_branch_ids"), branch.get("sibling_branch_ids")
            ));
            List<String> branchTargetEvidenceIds = extractStringList(firstNonNull(
                    decision.get("target_evidence_ids"),
                    firstNonNull(
                            decision.get("evidence_ids"),
                            firstNonNull(branch.get("target_evidence_ids"), branch.get("target_evidence_ids_json"))
                    )
            ));
            String branchReason = defaultIfBlank(
                    stringValue(firstNonNull(decision.get("branch_reason"), branch.get("branch_reason"))),
                    "COUNTERFACTUAL_RECHECK"
            );
            String branchStatus = defaultIfBlank(
                    stringValue(firstNonNull(decision.get("branch_status"), firstNonNull(branch.get("status"), branch.get("branch_status")))),
                    "ACTIVE_BRANCH"
            );
            if (!branchId.isBlank()) {
                counterfactualBranchIds.add(branchId);
            }
            if (!sessionId.isBlank()) {
                counterfactualSessionIds.add(sessionId);
            }
            if (!branchReason.isBlank()) {
                branchReasons.add(branchReason);
            }
            targetEvidenceIds.addAll(branchTargetEvidenceIds);
            if ((!branchId.isBlank() && branchId.equals(activeBranchId)) || isActiveBranchStatus(branchStatus)) {
                if (!branchId.isBlank()) {
                    activeCounterfactualBranchIds.add(branchId);
                }
                if (!sessionId.isBlank()) {
                    activeCounterfactualSessionIds.add(sessionId);
                }
            }
            counterfactualBranches.add(new ResearchCounterfactualBranchResponse(
                    branchId,
                    sessionId,
                    blankIfNull(stringValue(firstNonNull(branch.get("parent_branch_id"), decision.get("parent_branch_id")))),
                    parentSessionId,
                    branchReason,
                    branchStatus,
                    executionMode,
                    siblingBranchIds,
                    decisionType,
                    blankIfNull(stringValue(decision.get("verifier_scope"))),
                    blankIfNull(stringValue(firstNonNull(branch.get("hypothesis_summary"), decision.get("hypothesis_summary")))),
                    branchTargetEvidenceIds
            ));
        }

        List<Map<String, Object>> conflictedRows = extractListOfMaps(conflictReview.get("conflicted_rows"));
        if (conflictedRows.isEmpty()) {
            conflictedRows = rows.stream()
                    .filter(ResearchCounterfactualSummaryAssembler::isCounterfactualRow)
                    .toList();
        }
        boolean hasCounterfactualRecheck = !counterfactualBranches.isEmpty()
                || "COUNTERFACTUAL_RECHECK".equals(recoveryMode);
        return new ResearchCounterfactualSummaryResponse(
                hasCounterfactualRecheck,
                counterfactualBranches.size(),
                conflictedRows.size(),
                blankIfNull(localVerifierStatus),
                blankIfNull(globalVerifierDecision),
                blankIfNull(recoveryMode),
                List.copyOf(counterfactualBranchIds),
                List.copyOf(counterfactualSessionIds),
                List.copyOf(activeCounterfactualBranchIds),
                List.copyOf(activeCounterfactualSessionIds),
                List.copyOf(branchReasons),
                List.copyOf(targetEvidenceIds),
                counterfactualBranches
        );
    }

    private static boolean isCounterfactualRow(Map<String, Object> row) {
        String rowStatus = stringValue(row.get("row_status"));
        String verificationStatus = stringValue(row.get("verification_status"));
        return "CONFLICTED".equals(rowStatus)
                || "COUNTERFACTUAL_REQUIRED".equals(verificationStatus)
                || "COUNTERFACTUAL_RECHECK".equals(verificationStatus);
    }

    private static boolean isActiveBranchStatus(String branchStatus) {
        return "ACTIVE_BRANCH".equals(branchStatus) || "ACTIVE".equals(branchStatus);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
