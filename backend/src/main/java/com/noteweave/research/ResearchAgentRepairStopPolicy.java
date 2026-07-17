package com.noteweave.research;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Pure MA4I policy over a coordinator's already-authoritative snapshot.
 * It deliberately has no database or worker-supplied authority.
 */
public final class ResearchAgentRepairStopPolicy {

    private ResearchAgentRepairStopPolicy() { }

    public static Decision evaluate(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        if (snapshot.cancelled()) return Decision.stop(DecisionKind.STOPPED_CANCELLED, "run_cancelled");
        if (snapshot.deadlineReached()) return Decision.stop(DecisionKind.STOPPED_DEADLINE, "deadline_reached");
        if (!snapshot.budgetAllowsRepair()) return Decision.stop(DecisionKind.STOPPED_BUDGET_EXHAUSTED, "repair_budget_unavailable");
        int maximum = Math.max(0, snapshot.maxRepairPerCell());
        List<RepairTarget> targets = (snapshot.gaps() == null ? List.<Gap>of() : snapshot.gaps()).stream()
                .filter(Gap::repairRequired)
                .filter(Gap::runScoped)
                .filter(gap -> !gap.frozen())
                .filter(gap -> gap.repairCount() < maximum)
                .map(gap -> new RepairTarget(gap.cellKey(), gap.reasonDigest(), sorted(gap.excludedSourceIds())))
                .sorted(Comparator.comparing(RepairTarget::cellKey).thenComparing(RepairTarget::reasonDigest))
                .toList();
        if (targets.isEmpty()) return Decision.stop(DecisionKind.STOPPED_PARTIAL, "no_safe_repair_target");
        return new Decision(DecisionKind.COUNTERFACTUAL, "counterfactual_repair", targets);
    }

    private static List<String> sorted(Set<String> ids) {
        return (ids == null ? Set.<String>of() : ids).stream().filter(value -> value != null && !value.isBlank()).sorted().toList();
    }

    public enum DecisionKind { COUNTERFACTUAL, STOPPED_CANCELLED, STOPPED_DEADLINE, STOPPED_BUDGET_EXHAUSTED, STOPPED_PARTIAL }
    public record Snapshot(boolean cancelled, boolean deadlineReached, boolean budgetAllowsRepair, int maxRepairPerCell, List<Gap> gaps) { }
    public record Gap(String cellKey, boolean repairRequired, boolean runScoped, boolean frozen, int repairCount,
                      String reasonDigest, Set<String> excludedSourceIds) { }
    public record RepairTarget(String cellKey, String reasonDigest, List<String> excludedSourceIds) { }
    public record Decision(DecisionKind kind, String reason, List<RepairTarget> targets) {
        static Decision stop(DecisionKind kind, String reason) { return new Decision(kind, reason, List.of()); }
    }
}
