package com.noteweave.research;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Pure MA4I barrier: it has no database authority and therefore cannot advance a run by itself. */
public final class ResearchAgentWaveBarrier {

    private static final Set<String> ACTIVE = Set.of("PENDING", "CLAIMED", "RUNNING", "RETRY_WAIT");
    private static final Set<String> TERMINAL = Set.of("SUBMITTED", "FAILED", "CANCELLED", "EXPIRED");

    private ResearchAgentWaveBarrier() { }

    public static BarrierResult evaluate(List<TaskOutcome> tasks) {
        List<TaskOutcome> ordered = tasks == null ? List.of() : tasks.stream()
                .sorted(Comparator.comparing(TaskOutcome::taskId))
                .toList();
        List<String> active = ordered.stream().filter(task -> ACTIVE.contains(task.status()))
                .map(TaskOutcome::taskId).toList();
        if (!active.isEmpty()) return new BarrierResult(BarrierState.BLOCKED_ACTIVE, active);
        List<String> recovery = ordered.stream().filter(task -> "EXPIRED".equals(task.status()) && !task.recoveryDecisionFinalized())
                .map(TaskOutcome::taskId).toList();
        if (!recovery.isEmpty()) return new BarrierResult(BarrierState.BLOCKED_RECOVERY, recovery);
        List<String> unexplained = ordered.stream().filter(task -> !TERMINAL.contains(task.status()))
                .map(TaskOutcome::taskId).toList();
        if (!unexplained.isEmpty()) return new BarrierResult(BarrierState.BLOCKED_UNEXPLAINED, unexplained);
        return new BarrierResult(BarrierState.READY, List.of());
    }

    public enum BarrierState { READY, BLOCKED_ACTIVE, BLOCKED_RECOVERY, BLOCKED_UNEXPLAINED }
    public record TaskOutcome(String taskId, String status, boolean recoveryDecisionFinalized) { }
    public record BarrierResult(BarrierState state, List<String> blockingTaskIds) { }
}
