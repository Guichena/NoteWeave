package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.blankIfNull;
import static com.noteweave.research.ResearchReadModelMapper.booleanValue;
import static com.noteweave.research.ResearchReadModelMapper.castMapOrEmpty;
import static com.noteweave.research.ResearchReadModelMapper.extractListOfMaps;
import static com.noteweave.research.ResearchReadModelMapper.extractStringList;
import static com.noteweave.research.ResearchReadModelMapper.firstNonNull;
import static com.noteweave.research.ResearchReadModelMapper.intValue;
import static com.noteweave.research.ResearchReadModelMapper.readCheckpointStateLedgerSummaryResponse;
import static com.noteweave.research.ResearchReadModelMapper.readCounterfactualSummary;
import static com.noteweave.research.ResearchReadModelMapper.readRecoveryTargetsResponse;
import static com.noteweave.research.ResearchReadModelMapper.readVerifierGatedSummaryResponse;
import static com.noteweave.research.ResearchReadModelMapper.stringValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds checkpoint process, audit and recovery read-model projections. */
@Component
public class ResearchCheckpointProcessAssembler {

    private final ResearchVerifierGatedSummaryAssembler verifierGatedSummaryAssembler;
    private final ResearchCounterfactualSummaryAssembler counterfactualSummaryAssembler;

    public ResearchCheckpointProcessAssembler(
            ResearchVerifierGatedSummaryAssembler verifierGatedSummaryAssembler,
            ResearchCounterfactualSummaryAssembler counterfactualSummaryAssembler
    ) {
        this.verifierGatedSummaryAssembler = verifierGatedSummaryAssembler;
        this.counterfactualSummaryAssembler = counterfactualSummaryAssembler;
    }

    public ResearchProcessSummaryResponse buildProcessSummary(
            Map<String, Object> checkpointPayload,
            Map<String, Object> checkpointSummary
    ) {
        return new ResearchProcessSummaryResponse(
                estimateSourceScopeCount(checkpointPayload),
                buildSearchReadTimeline(checkpointPayload),
                buildSourceEvidenceSummary(checkpointPayload),
                buildAuditSummary(checkpointPayload, checkpointSummary)
        );
    }

    public Map<String, Object> buildVerifierGatedSummary(
            List<Map<String, Object>> rows,
            Map<String, Object> recoveryTargets
    ) {
        return verifierGatedSummaryAssembler.build(rows, recoveryTargets);
    }

    public ResearchCounterfactualSummaryResponse firstNonNullCounterfactualSummary(
            Map<String, Object> checkpointSummary,
            Map<String, Object> checkpointPayload
    ) {
        ResearchCounterfactualSummaryResponse summary = readCounterfactualSummary(
                castMapOrEmpty(checkpointSummary.get("counterfactual_summary"))
        );
        return summary != null ? summary : buildCounterfactualSummary(checkpointPayload);
    }

    public ResearchCounterfactualSummaryResponse buildCounterfactualSummary(
            Map<String, Object> checkpointPayload
    ) {
        ResearchCounterfactualSummaryResponse directSummary = readCounterfactualSummary(castMapOrEmpty(
                firstNonNull(
                        checkpointPayload.get("counterfactual_summary"),
                        castMapOrEmpty(checkpointPayload.get("report_structure")).get("counterfactual_summary")
                )
        ));
        if (directSummary != null) {
            return directSummary;
        }
        Map<String, Object> reportStructure = castMapOrEmpty(checkpointPayload.get("report_structure"));
        Map<String, Object> conflictReview = castMapOrEmpty(reportStructure.get("conflict_and_counterfactual_review"));
        Map<String, Object> stateLedger = castMapOrEmpty(checkpointPayload.get("state_ledger"));
        Map<String, Object> localVerifier = castMapOrEmpty(checkpointPayload.get("local_verifier"));
        Map<String, Object> globalVerifier = castMapOrEmpty(checkpointPayload.get("global_verifier"));
        return counterfactualSummaryAssembler.build(
                conflictReview,
                extractListOfMaps(conflictReview.get("branch_decisions")),
                extractListOfMaps(checkpointPayload.get("branch_decisions")),
                extractListOfMaps(stateLedger.get("branches")),
                extractListOfMaps(stateLedger.get("rows")),
                stringValue(stateLedger.get("active_branch_id")),
                stringValue(firstNonNull(
                        localVerifier.get("status"),
                        conflictReview.get("local_verifier_status")
                )),
                stringValue(firstNonNull(
                        globalVerifier.get("decision"),
                        conflictReview.get("global_verifier_decision")
                )),
                stringValue(firstNonNull(
                        reportStructure.get("recovery_mode"),
                        conflictReview.get("recovery_mode")
                ))
        );
    }

    public Map<String, Object> rawRecoveryTargets(Map<String, Object> checkpointPayload) {
        Map<String, Object> reportStructure = castMapOrEmpty(checkpointPayload.get("report_structure"));
        Map<String, Object> closedLoopState = castMapOrEmpty(reportStructure.get("closed_loop_state"));
        Map<String, Object> direct = castMapOrEmpty(firstNonNull(
                closedLoopState.get("recovery_targets"),
                castMapOrEmpty(reportStructure.get("recovery_status")).get("recovery_targets")
        ));
        if (!direct.isEmpty()) {
            return direct;
        }
        return buildRecoveryTargetsFromStopContract(castMapOrEmpty(checkpointPayload.get("stop_contract")));
    }

    public Map<String, Object> buildRecoveryTargetsFromStopContract(Map<String, Object> stopContract) {
        if (stopContract.isEmpty()) {
            return Map.of();
        }
        List<String> requirementIds = extractStringList(stopContract.get("recovery_target_requirement_ids"));
        List<String> requirementTypes = extractStringList(stopContract.get("recovery_target_requirement_types"));
        List<String> requirementLabels = extractStringList(stopContract.get("recovery_target_requirement_labels"));
        List<String> targetColumns = extractStringList(stopContract.get("recovery_target_columns"));
        List<String> targetQueries = extractStringList(stopContract.get("recovery_target_queries"));
        List<String> targetSources = extractStringList(stopContract.get("recovery_target_sources"));
        if (requirementIds.isEmpty()
                && requirementTypes.isEmpty()
                && requirementLabels.isEmpty()
                && targetColumns.isEmpty()
                && targetQueries.isEmpty()
                && targetSources.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> recoveryTargets = new LinkedHashMap<>();
        recoveryTargets.put("requirement_ids", requirementIds);
        recoveryTargets.put("requirement_types", requirementTypes);
        recoveryTargets.put("requirement_labels", requirementLabels);
        recoveryTargets.put("target_columns", targetColumns);
        recoveryTargets.put("target_queries", targetQueries);
        recoveryTargets.put("target_sources", targetSources);
        recoveryTargets.put("requirement_count", requirementIds.size());
        recoveryTargets.put("query_count", targetQueries.size());
        recoveryTargets.put("source_count", targetSources.size());
        recoveryTargets.put("column_count", targetColumns.size());
        return recoveryTargets;
    }

    private ResearchSearchReadTimelineResponse buildSearchReadTimeline(Map<String, Object> checkpointPayload) {
        List<Map<String, Object>> loopRounds = extractListOfMaps(checkpointPayload.get("loop_rounds"));
        Map<String, Object> loopDecision = castMapOrEmpty(checkpointPayload.get("loop_decision"));
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
                blankIfNull(stringValue(loopDecision.get("decision"))),
                blankIfNull(stringValue(loopDecision.get("reason"))),
                blankIfNull(stringValue(loopDecision.get("terminal_disposition"))),
                booleanValue(loopDecision.get("handoff_required")),
                blankIfNull(stringValue(loopDecision.get("abandon_reason"))),
                roundResponses
        );
    }

    private ResearchSourceEvidenceSummaryResponse buildSourceEvidenceSummary(
            Map<String, Object> checkpointPayload
    ) {
        Map<String, Object> reportStructure = castMapOrEmpty(checkpointPayload.get("report_structure"));
        Map<String, Object> sourceFoundation = castMapOrEmpty(reportStructure.get("source_foundation"));
        Map<String, Object> finalAnswer = castMapOrEmpty(reportStructure.get("final_answer"));
        return new ResearchSourceEvidenceSummaryResponse(
                blankIfNull(stringValue(finalAnswer.get("source_basis"))),
                blankIfNull(stringValue(sourceFoundation.get("primary_quality"))),
                blankIfNull(stringValue(sourceFoundation.get("quality_mix_label"))),
                blankIfNull(stringValue(sourceFoundation.get("read_strategy_mix_label"))),
                blankIfNull(stringValue(sourceFoundation.get("fetch_foundation_label"))),
                blankIfNull(stringValue(sourceFoundation.get("orchestration_foundation_label"))),
                extractListOfMaps(reportStructure.get("verified_findings")).size(),
                0
        );
    }

    private ResearchAuditSummaryResponse buildAuditSummary(
            Map<String, Object> checkpointPayload,
            Map<String, Object> checkpointSummary
    ) {
        Map<String, Object> safeSummary = castMapOrEmpty(checkpointSummary);
        Map<String, Object> localVerifier = castMapOrEmpty(firstNonNull(
                safeSummary.get("local_verifier"), checkpointPayload.get("local_verifier")
        ));
        Map<String, Object> globalVerifier = castMapOrEmpty(firstNonNull(
                safeSummary.get("global_verifier"), checkpointPayload.get("global_verifier")
        ));
        Map<String, Object> loopDecision = castMapOrEmpty(firstNonNull(
                safeSummary.get("loop_decision"), checkpointPayload.get("loop_decision")
        ));
        ResearchCounterfactualSummaryResponse counterfactualSummary = firstNonNullCounterfactualSummary(
                safeSummary, checkpointPayload);
        ResearchRecoveryTargetsResponse recoveryTargets = readRecoveryTargetsResponse(
                rawRecoveryTargets(checkpointPayload));
        ResearchVerifierGatedSummaryResponse verifierGatedSummary = readVerifierGatedSummaryResponse(
                castMapOrEmpty(safeSummary.get("verifier_gated_summary")));
        ResearchCheckpointStateLedgerSummaryResponse stateLedgerSummary = readCheckpointStateLedgerSummaryResponse(
                castMapOrEmpty(safeSummary.get("state_ledger")));
        return new ResearchAuditSummaryResponse(
                blankIfNull(stringValue(localVerifier.get("status"))),
                blankIfNull(stringValue(globalVerifier.get("decision"))),
                blankIfNull(stringValue(loopDecision.get("decision"))),
                counterfactualSummary != null && counterfactualSummary.hasCounterfactualRecheck(),
                counterfactualSummary == null ? 0 : counterfactualSummary.counterfactualBranchCount(),
                intValue(firstNonNull(safeSummary.get("checkpoint_no"), 1)),
                verifierGatedSummary == null ? 0 : verifierGatedSummary.blockedRowCount(),
                stateLedgerSummary == null ? 0 : stateLedgerSummary.conflictedRowCount(),
                verifierGatedSummary == null ? 0 : verifierGatedSummary.guardrailedRowCount(),
                recoveryTargets == null ? 0 : recoveryTargets.requirementCount()
        );
    }

    private int estimateSourceScopeCount(Map<String, Object> checkpointPayload) {
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        for (Map<String, Object> readWindow : extractListOfMaps(checkpointPayload.get("read_windows"))) {
            String sourceId = blankIfNull(stringValue(readWindow.get("source_id")));
            if (!sourceId.isBlank()) {
                sourceIds.add(sourceId);
            }
        }
        for (Map<String, Object> evidenceCard : extractListOfMaps(checkpointPayload.get("evidence_cards"))) {
            String sourceId = blankIfNull(stringValue(evidenceCard.get("source_id")));
            if (!sourceId.isBlank()) {
                sourceIds.add(sourceId);
            }
        }
        return sourceIds.size();
    }
}
