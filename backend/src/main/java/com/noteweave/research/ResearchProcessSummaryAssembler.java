package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.booleanValue;
import static com.noteweave.research.ResearchReadModelMapper.extractStringList;
import static com.noteweave.research.ResearchReadModelMapper.intValue;
import static com.noteweave.research.ResearchReadModelMapper.stringValue;
import static com.noteweave.research.ResearchReadModelMapper.blankIfNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds the process summary from an already assembled research closed-loop read model. */
@Component
public class ResearchProcessSummaryAssembler {

    public ResearchProcessSummaryResponse build(
            ResearchReportStructureResponse reportStructure,
            ResearchClosedLoopStateResponse closedLoopState,
            ResearchVerifierSummaryResponse verifierSummary,
            int sourceScopeCount,
            ResearchArtifactCandidateResponse researchArtifactCandidate
    ) {
        return new ResearchProcessSummaryResponse(
                sourceScopeCount,
                buildSearchReadTimeline(closedLoopState),
                buildSourceEvidenceSummary(reportStructure, researchArtifactCandidate),
                buildAuditSummary(closedLoopState, verifierSummary)
        );
    }

    private ResearchSearchReadTimelineResponse buildSearchReadTimeline(
            ResearchClosedLoopStateResponse closedLoopState
    ) {
        List<Map<String, Object>> loopRounds = closedLoopState == null ? List.of() : closedLoopState.loopRounds();
        List<ResearchLoopRoundSummaryResponse> roundResponses = new ArrayList<>();
        LinkedHashSet<String> allQueries = new LinkedHashSet<>();
        int totalSearchHitCount = 0;
        int totalReadWindowCount = 0;
        int totalEvidenceCardCount = 0;
        for (Map<String, Object> round : loopRounds) {
            List<String> searchQueries = extractStringList(round.get("search_queries"));
            allQueries.addAll(searchQueries);
            totalSearchHitCount += intValue(round.get("search_hit_count"));
            totalReadWindowCount += intValue(round.get("read_window_count"));
            totalEvidenceCardCount += intValue(round.get("evidence_card_count"));
            roundResponses.add(new ResearchLoopRoundSummaryResponse(
                    intValue(round.get("round_no")),
                    intValue(round.get("search_hit_count")),
                    intValue(round.get("read_window_count")),
                    intValue(round.get("evidence_card_count")),
                    searchQueries,
                    extractStringList(round.get("evidence_ids")),
                    blankIfNull(stringValue(round.get("branch_decision"))),
                    blankIfNull(stringValue(round.get("global_decision")))
            ));
        }
        return new ResearchSearchReadTimelineResponse(
                loopRounds.size(),
                totalSearchHitCount,
                totalReadWindowCount,
                totalEvidenceCardCount,
                new ArrayList<>(allQueries),
                closedLoopState == null ? "" : blankIfNull(closedLoopState.finalLoopDecision()),
                closedLoopState == null ? "" : blankIfNull(stringValue(closedLoopState.loopDecisionPayload().get("reason"))),
                closedLoopState == null ? "" : blankIfNull(stringValue(closedLoopState.loopDecisionPayload().get("terminal_disposition"))),
                closedLoopState != null && booleanValue(closedLoopState.loopDecisionPayload().get("handoff_required")),
                closedLoopState == null ? "" : blankIfNull(stringValue(closedLoopState.loopDecisionPayload().get("abandon_reason"))),
                roundResponses
        );
    }

    private ResearchSourceEvidenceSummaryResponse buildSourceEvidenceSummary(
            ResearchReportStructureResponse reportStructure,
            ResearchArtifactCandidateResponse researchArtifactCandidate
    ) {
        Map<String, Object> sourceFoundation = reportStructure == null ? Map.of() : reportStructure.sourceFoundation();
        Map<String, Object> finalAnswer = reportStructure == null ? Map.of() : reportStructure.finalAnswer();
        String sourceBasis = blankIfNull(stringValue(finalAnswer.get("source_basis")));
        if (sourceBasis.isBlank() && researchArtifactCandidate != null) {
            sourceBasis = blankIfNull(researchArtifactCandidate.sourceBasis());
        }
        return new ResearchSourceEvidenceSummaryResponse(
                sourceBasis,
                blankIfNull(stringValue(sourceFoundation.get("primary_quality"))),
                blankIfNull(stringValue(sourceFoundation.get("quality_mix_label"))),
                blankIfNull(stringValue(sourceFoundation.get("read_strategy_mix_label"))),
                blankIfNull(stringValue(sourceFoundation.get("fetch_foundation_label"))),
                blankIfNull(stringValue(sourceFoundation.get("orchestration_foundation_label"))),
                reportStructure == null ? 0 : reportStructure.verifiedFindings().size(),
                researchArtifactCandidate == null ? 0 : researchArtifactCandidate.citationCount()
        );
    }

    private ResearchAuditSummaryResponse buildAuditSummary(
            ResearchClosedLoopStateResponse closedLoopState,
            ResearchVerifierSummaryResponse verifierSummary
    ) {
        ResearchVerifierGatedSummaryResponse verifierGatedSummary = verifierSummary == null
                ? null
                : verifierSummary.verifierGatedSummary();
        ResearchRecoveryTargetsResponse recoveryTargets = verifierSummary == null
                ? null
                : verifierSummary.recoveryTargets();
        ResearchCounterfactualSummaryResponse counterfactualSummary = closedLoopState == null
                ? null
                : closedLoopState.counterfactualSummary();
        return new ResearchAuditSummaryResponse(
                verifierSummary == null ? "" : blankIfNull(verifierSummary.localVerifierStatus()),
                verifierSummary == null ? "" : blankIfNull(verifierSummary.globalVerifierDecision()),
                verifierSummary == null ? "" : blankIfNull(verifierSummary.finalLoopDecision()),
                counterfactualSummary != null && counterfactualSummary.hasCounterfactualRecheck(),
                counterfactualSummary == null ? 0 : counterfactualSummary.counterfactualBranchCount(),
                closedLoopState == null ? 0 : closedLoopState.checkpoints().size(),
                verifierGatedSummary == null ? 0 : verifierGatedSummary.blockedRowCount(),
                closedLoopState == null ? 0 : closedLoopState.stateLedger().conflictedRowCount(),
                verifierGatedSummary == null ? 0 : verifierGatedSummary.guardrailedRowCount(),
                recoveryTargets == null ? 0 : recoveryTargets.requirementCount()
        );
    }
}
