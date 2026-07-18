package com.noteweave.answer.strategy;

import com.noteweave.common.Ids;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RetrievalOrchestrator {

    private final Map<String, EvidenceRetriever> retrievers;
    private final EvidenceBudgeter evidenceBudgeter;
    private final MeterRegistry meterRegistry;
    private final EvidenceScopeGuard evidenceScopeGuard;
    private final EvidenceOwnershipGuard evidenceOwnershipGuard;

    @Autowired
    public RetrievalOrchestrator(
            List<EvidenceRetriever> candidates,
            EvidenceBudgeter evidenceBudgeter,
            MeterRegistry meterRegistry,
            EvidenceScopeGuard evidenceScopeGuard,
            EvidenceOwnershipGuard evidenceOwnershipGuard
    ) {
        this.retrievers = index(candidates);
        this.evidenceBudgeter = evidenceBudgeter;
        this.meterRegistry = meterRegistry;
        this.evidenceScopeGuard = evidenceScopeGuard;
        this.evidenceOwnershipGuard = evidenceOwnershipGuard;
    }

    RetrievalOrchestrator(List<EvidenceRetriever> candidates) {
        this(candidates, new EvidenceBudgeter(), new SimpleMeterRegistry(),
                new EvidenceScopeGuard(), EvidenceOwnershipGuard.trustValidatedRetriever());
    }

    RetrievalOrchestrator(
            List<EvidenceRetriever> candidates,
            EvidenceBudgeter evidenceBudgeter,
            MeterRegistry meterRegistry
    ) {
        this(candidates, evidenceBudgeter, meterRegistry,
                new EvidenceScopeGuard(), EvidenceOwnershipGuard.trustValidatedRetriever());
    }

    RetrievalOrchestrator(
            List<EvidenceRetriever> candidates,
            EvidenceBudgeter evidenceBudgeter,
            MeterRegistry meterRegistry,
            EvidenceScopeGuard evidenceScopeGuard
    ) {
        this(candidates, evidenceBudgeter, meterRegistry, evidenceScopeGuard,
                EvidenceOwnershipGuard.trustValidatedRetriever());
    }

    public EvidenceBundle execute(AnswerContext context, RetrievalPlan plan) {
        long retrievalStarted = System.nanoTime();
        List<EvidenceBundle.Evidence> collected = new ArrayList<>();
        List<String> degradationReasons = new ArrayList<>();
        List<RetrievalExecutionTrace.StepTrace> stepTraces = new ArrayList<>();
        Map<String, String> metadata = new HashMap<>();
        int rawCandidateCount = 0;
        int admittedCandidateCount = 0;
        for (int stepIndex = 0; stepIndex < plan.steps().size(); stepIndex++) {
            RetrievalPlan.Step step = plan.steps().get(stepIndex);
            long stepStarted = System.nanoTime();
            EvidenceRetriever retriever = retrievers.get(step.channel());
            if (retriever == null) {
                String reason = "missing_retriever:" + step.channel();
                degradationReasons.add(reason);
                long latencyNanos = elapsedNanos(stepStarted);
                stepTraces.add(new RetrievalExecutionTrace.StepTrace(
                        stepIndex, step.channel(), step.candidateLimit(), 0, 0,
                        toMicros(latencyNanos), true, List.of(reason), Map.of()));
                recordStepMetrics(plan.version(), step.channel(), latencyNanos, 0, 0, true);
                continue;
            }
            EvidenceRetrievalResult result = retriever.retrieve(context, plan, step);
            List<EvidenceBundle.Evidence> admitted = evidenceBudgeter.limitCandidates(
                    result.evidence(), step.candidateLimit());
            try {
                evidenceScopeGuard.requireAllowed(context, plan, admitted);
                evidenceOwnershipGuard.requireCurrent(context, admitted);
            } catch (com.noteweave.common.BusinessException ex) {
                meterRegistry.counter(
                        "noteweave.retrieval.scope_violation",
                        "mode", plan.mode() == null ? "unknown" : plan.mode().name(),
                        "channel", step.channel()
                ).increment();
                throw ex;
            }
            int rawCount = result.evidence().size();
            int admittedCount = admitted.size();
            rawCandidateCount += rawCount;
            admittedCandidateCount += admittedCount;
            collected.addAll(admitted);
            result.metadata().forEach((key, value) -> {
                String previous = metadata.putIfAbsent(key, value);
                if (previous != null && !previous.equals(value)) {
                    throw new IllegalStateException("Conflicting retrieval metadata key: " + key);
                }
            });
            if (result.degraded()) {
                degradationReasons.addAll(result.degradationReasons());
            }
            long latencyNanos = elapsedNanos(stepStarted);
            stepTraces.add(new RetrievalExecutionTrace.StepTrace(
                    stepIndex,
                    step.channel(),
                    step.candidateLimit(),
                    rawCount,
                    admittedCount,
                    toMicros(latencyNanos),
                    result.degraded(),
                    result.degradationReasons(),
                    result.measurements()
            ));
            recordStepMetrics(
                    plan.version(), step.channel(), latencyNanos,
                    rawCount, admittedCount, result.degraded());
        }
        List<EvidenceBundle.Evidence> selected = evidenceBudgeter.select(collected, plan.budget());
        int selectedCharacters = selected.stream()
                .mapToInt(evidence -> Math.max(0, evidence.characterCost()))
                .sum();
        List<RetrievalExecutionTrace.SelectedEvidenceTrace> selectedTrace =
                java.util.stream.IntStream.range(0, selected.size())
                        .mapToObj(index -> selectedTrace(index, selected.get(index)))
                        .toList();
        RetrievalExecutionTrace trace = new RetrievalExecutionTrace(
                RetrievalExecutionTrace.SCHEMA_VERSION,
                plan.version(),
                toMicros(elapsedNanos(retrievalStarted)),
                rawCandidateCount,
                admittedCandidateCount,
                selected.size(),
                selectedCharacters,
                stepTraces,
                selectedTrace
        );
        recordBundleMetrics(
                plan.version(), plan.mode().name(), trace, !degradationReasons.isEmpty());
        return new EvidenceBundle(
                Ids.newId(),
                plan.version(),
                selected,
                !degradationReasons.isEmpty(),
                degradationReasons,
                Instant.now(),
                metadata,
                trace
        );
    }

    private Map<String, EvidenceRetriever> index(List<EvidenceRetriever> candidates) {
        Map<String, EvidenceRetriever> indexed = new HashMap<>();
        for (EvidenceRetriever candidate : candidates) {
            EvidenceRetriever previous = indexed.putIfAbsent(candidate.channel(), candidate);
            if (previous != null) {
                throw new IllegalStateException(
                        "Multiple EvidenceRetriever implementations for " + candidate.channel());
            }
        }
        return Map.copyOf(indexed);
    }

    private RetrievalExecutionTrace.SelectedEvidenceTrace selectedTrace(
            int index,
            EvidenceBundle.Evidence evidence
    ) {
        return new RetrievalExecutionTrace.SelectedEvidenceTrace(
                index + 1,
                evidence.evidenceId(),
                evidence.kind(),
                evidence.rawScore(),
                evidence.fusedScore(),
                evidence.rerankScore(),
                evidence.characterCost()
        );
    }

    private void recordStepMetrics(
            String planVersion,
            String channel,
            long latencyNanos,
            int rawCandidateCount,
            int admittedCandidateCount,
            boolean degraded
    ) {
        Timer.builder("noteweave.retrieval.step.latency")
                .tag("plan_version", planVersion)
                .tag("channel", channel)
                .tag("degraded", Boolean.toString(degraded))
                .register(meterRegistry)
                .record(latencyNanos, TimeUnit.NANOSECONDS);
        candidateSummary(planVersion, channel, "raw").record(rawCandidateCount);
        candidateSummary(planVersion, channel, "admitted").record(admittedCandidateCount);
    }

    private DistributionSummary candidateSummary(
            String planVersion,
            String channel,
            String stage
    ) {
        return DistributionSummary.builder("noteweave.retrieval.step.candidates")
                .tag("plan_version", planVersion)
                .tag("channel", channel)
                .tag("stage", stage)
                .register(meterRegistry);
    }

    private void recordBundleMetrics(
            String planVersion,
            String mode,
            RetrievalExecutionTrace trace,
            boolean degraded
    ) {
        DistributionSummary.builder("noteweave.retrieval.bundle.selected_evidence")
                .tag("plan_version", planVersion)
                .tag("mode", mode)
                .register(meterRegistry)
                .record(trace.selectedEvidenceCount());
        DistributionSummary.builder("noteweave.retrieval.bundle.selected_characters")
                .tag("plan_version", planVersion)
                .tag("mode", mode)
                .register(meterRegistry)
                .record(trace.selectedEvidenceCharacters());
        if (degraded) {
            meterRegistry.counter(
                    "noteweave.retrieval.bundle.degraded",
                    "plan_version", planVersion,
                    "mode", mode
            ).increment();
        }
    }

    private long elapsedNanos(long started) {
        return Math.max(0, System.nanoTime() - started);
    }

    private long toMicros(long nanos) {
        return TimeUnit.NANOSECONDS.toMicros(Math.max(0, nanos));
    }
}
