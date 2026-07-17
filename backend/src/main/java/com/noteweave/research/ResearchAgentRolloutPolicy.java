package com.noteweave.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure MA6 decision policy; observation and taskization remain separate. */
public final class ResearchAgentRolloutPolicy {
    public enum Action { CONTINUE, PAUSE_NEW_INITIAL_WAVES }

    private final int minimumTaskSample;
    private final double maxTerminalFailureRate;
    private final double maxDeliveryFailureRate;
    private final double maxRejectedMergeRate;

    public ResearchAgentRolloutPolicy(int minimumTaskSample,
                                      double maxTerminalFailureRate,
                                      double maxDeliveryFailureRate,
                                      double maxRejectedMergeRate) {
        if (minimumTaskSample < 1) throw new IllegalArgumentException("minimum task sample must be positive");
        requireRate(maxTerminalFailureRate, "terminal failure threshold");
        requireRate(maxDeliveryFailureRate, "delivery failure threshold");
        requireRate(maxRejectedMergeRate, "rejected merge threshold");
        this.minimumTaskSample = minimumTaskSample;
        this.maxTerminalFailureRate = maxTerminalFailureRate;
        this.maxDeliveryFailureRate = maxDeliveryFailureRate;
        this.maxRejectedMergeRate = maxRejectedMergeRate;
    }

    public Decision evaluate(HealthSnapshot snapshot) {
        snapshot.requireValid();
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("terminal_failure_rate", rate(snapshot.terminalFailureCount(), snapshot.taskCount()));
        rates.put("delivery_failure_rate", rate(snapshot.deliveryFailureCount(), snapshot.taskCount()));
        rates.put("rejected_merge_rate", rate(
                snapshot.rejectedMergeCount(), snapshot.rejectedMergeCount() + snapshot.acceptedMergeCount()));
        if (snapshot.taskCount() < minimumTaskSample) {
            return new Decision(Action.CONTINUE, List.of("INSUFFICIENT_SAMPLE"), Map.copyOf(rates));
        }
        List<String> reasons = new ArrayList<>();
        if (rates.get("terminal_failure_rate") > maxTerminalFailureRate) {
            reasons.add("TERMINAL_FAILURE_RATE_EXCEEDED");
        }
        if (rates.get("delivery_failure_rate") > maxDeliveryFailureRate) {
            reasons.add("DELIVERY_FAILURE_RATE_EXCEEDED");
        }
        if (rates.get("rejected_merge_rate") > maxRejectedMergeRate) {
            reasons.add("REJECTED_MERGE_RATE_EXCEEDED");
        }
        return reasons.isEmpty()
                ? new Decision(Action.CONTINUE, List.of("HEALTHY"), Map.copyOf(rates))
                : new Decision(Action.PAUSE_NEW_INITIAL_WAVES, List.copyOf(reasons), Map.copyOf(rates));
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0d : (double) numerator / denominator;
    }

    private static void requireRate(double value, String label) {
        if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(label + " must be between zero and one");
        }
    }

    public record HealthSnapshot(long taskCount, long terminalFailureCount, long deliveryFailureCount,
                                 long rejectedMergeCount, long acceptedMergeCount) {
        void requireValid() {
            if (taskCount < 0 || terminalFailureCount < 0 || deliveryFailureCount < 0
                    || rejectedMergeCount < 0 || acceptedMergeCount < 0) {
                throw new IllegalArgumentException("rollout health counters must be non-negative");
            }
        }
    }

    public record Decision(Action action, List<String> reasonCodes, Map<String, Double> observedRates) {
        public boolean allowsInitialWave() { return action == Action.CONTINUE; }
    }
}
