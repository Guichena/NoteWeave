package com.noteweave.research;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Low-cardinality operational metrics for the atomic completion boundary. */
@Component
class ResearchAgentCompletionMetrics {
    private static final String TRANSACTION_BEGIN = "transaction_begin";

    private final MeterRegistry meters;
    private final ThreadLocal<String> transactionStage = new ThreadLocal<>();

    ResearchAgentCompletionMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    Timer.Sample startLatency() {
        return Timer.start(meters);
    }

    void stopLatency(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(Timer.builder("research.agent.completion.latency").register(meters));
        }
    }

    void recordValidatedPayload(int canonicalBytes, int candidateCells) {
        DistributionSummary.builder("research.agent.completion.payload.bytes")
                .baseUnit("bytes")
                .register(meters)
                .record(canonicalBytes);
        DistributionSummary.builder("research.agent.completion.cells")
                .baseUnit("cells")
                .register(meters)
                .record(candidateCells);
    }

    void recordCommit(ResearchAgentCompletionReceipt receipt) {
        Counter.builder("research.agent.completion.commit.total").register(meters).increment();
        if (receipt != null && receipt.budget() != null && receipt.budget().released() != null) {
            receipt.budget().released().forEach((dimension, released) ->
                    Counter.builder("research.agent.budget.released")
                            .tag("dimension", stableTag(dimension))
                            .register(meters)
                            .increment(released == null ? 0.0 : released.doubleValue()));
        }
    }

    void recordReplay() {
        Counter.builder("research.agent.completion.replay.total").register(meters).increment();
    }

    void recordConflict(String type) {
        Counter.builder("research.agent.completion.conflict.total")
                .tag("type", stableTag(type))
                .register(meters)
                .increment();
    }

    void beginTransaction() {
        transactionStage.set(TRANSACTION_BEGIN);
    }

    void markTransactionStage(ResearchAgentCompletionFaultInjector.Stage stage) {
        if (stage != null) transactionStage.set(stableTag(stage.name()));
    }

    void recordRollback() {
        String stage = transactionStage.get();
        Counter.builder("research.agent.completion.rollback.total")
                .tag("stage", stage == null ? TRANSACTION_BEGIN : stage)
                .register(meters)
                .increment();
    }

    void clearTransaction() {
        transactionStage.remove();
    }

    private String stableTag(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }
}
