package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One durable MA4I boundary from a failed-wave snapshot to a repair decision. */
@Service
public class ResearchAgentRepairAdvancementService {
    private static final int MAX_REPAIR_PER_CELL = 2;
    private final ResearchAgentGapProjectionService gaps;
    private final ResearchAgentRunAdvancementService advancements;
    private final ResearchAgentTaskCoordinatorService coordinator;
    private final ResearchAgentLocalReplanRecorder localReplans;

    public ResearchAgentRepairAdvancementService(ResearchAgentGapProjectionService gaps,
                                                 ResearchAgentRunAdvancementService advancements,
                                                 ResearchAgentTaskCoordinatorService coordinator,
                                                 ResearchAgentLocalReplanRecorder localReplans) {
        this.gaps = gaps;
        this.advancements = advancements;
        this.coordinator = coordinator;
        this.localReplans = localReplans;
    }

    @Transactional
    public RepairReceipt advanceAndTaskize(RepairAdvanceCommand command) {
        validate(command);
        List<ResearchAgentGapProjectionService.RepairGap> projected = gaps.projectRepairableGaps(
                command.researchRunId(), command.failedWaveNo());
        ResearchAgentRepairStopPolicy.Decision decision = ResearchAgentRepairStopPolicy.evaluate(
                new ResearchAgentRepairStopPolicy.Snapshot(false, false, true, MAX_REPAIR_PER_CELL,
                        projected.stream().map(gap -> new ResearchAgentRepairStopPolicy.Gap(
                                gap.cellKey(), gap.noActiveTask(), true, gap.frozen(), gap.repairCount(),
                                gap.reasonDigest(), java.util.Set.copyOf(gap.excludedSourceIds()))).toList()));
        ResearchAgentAdvanceDecisionCanonicalizer.CanonicalDecision canonical =
                ResearchAgentAdvanceDecisionCanonicalizer.canonicalize(new ResearchAgentAdvanceDecisionCanonicalizer.DecisionInput(
                        decision.kind().name(), command.failedWaveNo(), command.expectedCheckpointSeq(),
                        decision.targets().stream().map(ResearchAgentRepairStopPolicy.RepairTarget::cellKey).toList(),
                        decisionFacts(decision)));
        Map<String, Object> summary = new LinkedHashMap<>(command.summary());
        summary.put("decision_kind", decision.kind().name());
        summary.put("decision_digest", canonical.digest());
        summary.put("decision_json", canonical.canonicalJson());
        ResearchAgentRunAdvancementService.AdvanceReceipt advancement = advancements.advance(
                new ResearchAgentRunAdvancementService.AdvanceCommand(command.researchRunId(), command.advanceKey(), command.coordinatorId(),
                        command.expectedCheckpointSeq(), canonical.digest(), command.failedWaveNo(), command.roundNo(),
                        command.planRevision(), command.entitySetVersion(), command.ledgerHash(), command.taskHighWaterMark(),
                        command.candidateHighWaterMark(), command.mergeHighWaterMark(), command.budgetSummary(), summary));
        if (!advancement.idempotentReplay()
                && decision.kind() == ResearchAgentRepairStopPolicy.DecisionKind.COUNTERFACTUAL) {
            localReplans.record(command.researchRunId(), command.planRevision(), canonical.digest(),
                    repairCause(projected), decision.targets());
        }
        ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization;
        if (decision.kind() == ResearchAgentRepairStopPolicy.DecisionKind.COUNTERFACTUAL) {
            taskization = coordinator.planCounterfactualRepairs(new ResearchAgentTaskCoordinatorService.CounterfactualRepairCommand(
                    command.researchRunId(), command.expectedCheckpointSeq(), command.repairWaveNo(),
                    decision.targets().stream().map(target -> new ResearchAgentTaskCoordinatorService.CounterfactualTarget(
                            target.cellKey(), target.reasonDigest(), target.excludedSourceIds())).toList()));
        } else {
            taskization = new ResearchAgentTaskCoordinatorService.CoordinatorReceipt(0, 0, 0, 0);
        }
        return new RepairReceipt(advancement, decision.kind(), canonical.digest(), taskization);
    }

    private Map<String, Object> decisionFacts(ResearchAgentRepairStopPolicy.Decision decision) {
        Map<String, Object> facts = new LinkedHashMap<>();
        Map<String, Object> reasons = new LinkedHashMap<>();
        Map<String, Object> exclusions = new LinkedHashMap<>();
        decision.targets().stream().sorted(java.util.Comparator.comparing(ResearchAgentRepairStopPolicy.RepairTarget::cellKey)).forEach(target -> {
            reasons.put(target.cellKey(), target.reasonDigest());
            exclusions.put(target.cellKey(), target.excludedSourceIds());
        });
        facts.put("reason", decision.reason()); facts.put("reason_digests", reasons); facts.put("excluded_source_ids", exclusions);
        return facts;
    }

    private String repairCause(List<ResearchAgentGapProjectionService.RepairGap> projected) {
        List<String> reasons = projected.stream()
                .flatMap(gap -> gap.reasonCodes().stream())
                .map(value -> value == null ? "" : value.toUpperCase(java.util.Locale.ROOT))
                .toList();
        if (projected.stream().anyMatch(gap -> !gap.verifierDecisionIds().isEmpty())
                || reasons.stream().anyMatch(value -> value.contains("CONFLICT") || value.contains("QUORUM"))) {
            return "EVIDENCE_CONFLICT";
        }
        if (reasons.stream().anyMatch(value -> value.contains("NO_SUPPORTED_CANDIDATE")
                || value.contains("NON_EXACT_QUOTE") || value.contains("NO_VALID")
                || value.contains("EVIDENCE_ONLY"))) {
            return "NO_VALID_QUOTE";
        }
        if (reasons.stream().anyMatch(value -> value.contains("NO_RESULT") || value.contains("SEARCH_EMPTY"))) {
            return "NO_RESULTS";
        }
        if (reasons.stream().anyMatch(value -> value.contains("FETCH") || value.contains("READ"))) {
            return "FETCH_FAILED";
        }
        return "PROVIDER_FAILED";
    }

    private void validate(RepairAdvanceCommand command) {
        if (command == null || blank(command.researchRunId()) || blank(command.advanceKey()) || blank(command.coordinatorId())
                || blank(command.ledgerHash()) || command.expectedCheckpointSeq() < 0 || command.failedWaveNo() < 1
                || command.repairWaveNo() < 1 || command.roundNo() < 1 || command.planRevision() < 0
                || command.entitySetVersion() < 0 || command.taskHighWaterMark() < 0 || command.candidateHighWaterMark() < 0
                || command.mergeHighWaterMark() < 0 || command.budgetSummary() == null || command.summary() == null) {
            throw new BusinessException("RESEARCH_AGENT_REPAIR_ADVANCEMENT_INVALID", "Repair advancement contract is invalid");
        }
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record RepairAdvanceCommand(String researchRunId, String advanceKey, String coordinatorId, int expectedCheckpointSeq,
                                       int failedWaveNo, int repairWaveNo, int roundNo, int planRevision, int entitySetVersion,
                                       String ledgerHash, long taskHighWaterMark, long candidateHighWaterMark,
                                       long mergeHighWaterMark, Map<String, Object> budgetSummary, Map<String, Object> summary) { }
    public record RepairReceipt(ResearchAgentRunAdvancementService.AdvanceReceipt advancement,
                                ResearchAgentRepairStopPolicy.DecisionKind decisionKind, String decisionDigest,
                                ResearchAgentTaskCoordinatorService.CoordinatorReceipt taskization) { }
}
