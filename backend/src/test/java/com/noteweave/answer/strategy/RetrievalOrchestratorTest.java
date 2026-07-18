package com.noteweave.answer.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetrievalOrchestratorTest {

    @Test
    void shouldApplyEvidenceBudgetAcrossRetrieverOutput() {
        EvidenceRetriever retriever = new EvidenceRetriever() {
            @Override
            public String channel() {
                return "fixture";
            }

            @Override
            public EvidenceRetrievalResult retrieve(
                    AnswerContext context,
                    RetrievalPlan plan,
                    RetrievalPlan.Step step
            ) {
                return EvidenceRetrievalResult.success(
                        List.of(evidence("low", 1), evidence("high", 9), evidence("mid", 5)));
            }
        };
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                List.of(retriever), new EvidenceBudgeter(), meterRegistry);
        RetrievalPlan plan = new RetrievalPlan(
                "fixture-v1",
                AnswerMode.QA,
                List.of(new RetrievalPlan.Step("fixture", 10, 1, Map.of())),
                new RetrievalPlan.Budget(2, 1000, 0, 0)
        );

        EvidenceBundle bundle = orchestrator.execute(context(), plan);

        assertThat(bundle.evidence()).extracting(EvidenceBundle.Evidence::evidenceId)
                .containsExactly("high", "mid");
        assertThat(bundle.degraded()).isFalse();
        assertThat(bundle.trace().schemaVersion())
                .isEqualTo(RetrievalExecutionTrace.SCHEMA_VERSION);
        assertThat(bundle.trace().planVersion()).isEqualTo("fixture-v1");
        assertThat(bundle.trace().rawCandidateCount()).isEqualTo(3);
        assertThat(bundle.trace().admittedCandidateCount()).isEqualTo(3);
        assertThat(bundle.trace().selectedEvidenceCount()).isEqualTo(2);
        assertThat(bundle.trace().selectedEvidenceCharacters()).isEqualTo(7);
        assertThat(bundle.trace().steps()).singleElement().satisfies(step -> {
            assertThat(step.channel()).isEqualTo("fixture");
            assertThat(step.rawCandidateCount()).isEqualTo(3);
            assertThat(step.admittedCandidateCount()).isEqualTo(3);
            assertThat(step.latencyMicros()).isGreaterThanOrEqualTo(0);
            assertThat(step.measurements()).isEmpty();
        });
        assertThat(bundle.trace().selectedEvidence())
                .extracting(RetrievalExecutionTrace.SelectedEvidenceTrace::evidenceId)
                .containsExactly("high", "mid");
        assertThat(meterRegistry.get("noteweave.retrieval.step.latency")
                .tags("channel", "fixture", "degraded", "false").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("noteweave.retrieval.step.candidates")
                .tags("channel", "fixture", "stage", "raw").summary().totalAmount()).isEqualTo(3);
        assertThat(meterRegistry.get("noteweave.retrieval.bundle.selected_evidence")
                .tag("mode", "QA").summary().totalAmount()).isEqualTo(2);
    }

    @Test
    void shouldEnforcePerChannelCandidateLimitBeforeGlobalRanking() {
        EvidenceRetriever retriever = retriever(List.of(
                evidence("first", 1), evidence("second", 2), evidence("excluded-high", 99)));
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(List.of(retriever));
        RetrievalPlan plan = new RetrievalPlan(
                "fixture-v1",
                AnswerMode.QA,
                List.of(new RetrievalPlan.Step("fixture", 2, 1, Map.of())),
                new RetrievalPlan.Budget(3, 1000, 0, 0)
        );

        EvidenceBundle bundle = orchestrator.execute(context(), plan);

        assertThat(bundle.evidence()).extracting(EvidenceBundle.Evidence::evidenceId)
                .containsExactly("second", "first");
    }

    @Test
    void shouldSelectHighestRankedEvidenceWithinCharacterBudget() {
        EvidenceRetriever retriever = retriever(List.of(
                evidence("high", 9, 6),
                evidence("mid", 5, 5),
                evidence("low", 1, 4)
        ));
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(List.of(retriever));
        RetrievalPlan plan = new RetrievalPlan(
                "fixture-v1",
                AnswerMode.QA,
                List.of(new RetrievalPlan.Step("fixture", 3, 1, Map.of())),
                new RetrievalPlan.Budget(3, 10, 0, 0)
        );

        EvidenceBundle bundle = orchestrator.execute(context(), plan);

        assertThat(bundle.evidence()).extracting(EvidenceBundle.Evidence::evidenceId)
                .containsExactly("high", "low");
        assertThat(bundle.evidence()).extracting(EvidenceBundle.Evidence::characterCost)
                .containsExactly(6, 4);
    }

    @Test
    void shouldMarkMissingChannelAsDegradedInsteadOfUsingHiddenFallback() {
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(List.of());
        RetrievalPlan plan = new RetrievalPlan(
                "fixture-v1",
                AnswerMode.QA,
                List.of(new RetrievalPlan.Step("missing", 10, 1, Map.of())),
                null
        );

        EvidenceBundle bundle = orchestrator.execute(context(), plan);

        assertThat(bundle.evidence()).isEmpty();
        assertThat(bundle.degraded()).isTrue();
        assertThat(bundle.degradationReasons()).containsExactly("missing_retriever:missing");
        assertThat(bundle.trace().steps()).singleElement().satisfies(step -> {
            assertThat(step.degraded()).isTrue();
            assertThat(step.degradationReasons()).containsExactly("missing_retriever:missing");
            assertThat(step.measurements()).isEmpty();
        });
    }

    @Test
    void shouldPropagateRetrieverDegradationToTraceAndMetrics() {
        EvidenceRetriever retriever = new EvidenceRetriever() {
            @Override
            public String channel() {
                return "fixture";
            }

            @Override
            public EvidenceRetrievalResult retrieve(
                    AnswerContext context,
                    RetrievalPlan plan,
                    RetrievalPlan.Step step
            ) {
                return new EvidenceRetrievalResult(
                        List.of(evidence("fallback", 1)),
                        Map.of(),
                        true,
                        List.of("qa_mysql_fallback"),
                        Map.of("mysql_fallback_used", 1L)
                );
            }
        };
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                List.of(retriever), new EvidenceBudgeter(), meterRegistry);
        RetrievalPlan plan = new RetrievalPlan(
                "fixture-v1", AnswerMode.QA,
                List.of(new RetrievalPlan.Step("fixture", 2, 1, Map.of())),
                new RetrievalPlan.Budget(2, 100, 0, 0));

        EvidenceBundle bundle = orchestrator.execute(context(), plan);

        assertThat(bundle.degraded()).isTrue();
        assertThat(bundle.degradationReasons()).containsExactly("qa_mysql_fallback");
        assertThat(bundle.trace().steps()).singleElement().satisfies(step -> {
            assertThat(step.degraded()).isTrue();
            assertThat(step.measurements()).containsEntry("mysql_fallback_used", 1L);
        });
        assertThat(meterRegistry.get("noteweave.retrieval.step.latency")
                .tags("channel", "fixture", "degraded", "true").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("noteweave.retrieval.bundle.degraded")
                .tag("mode", "QA").counter().count()).isEqualTo(1);
    }

    @Test
    void shouldRejectPassageEvidenceOutsideExplicitSourceScope() {
        EvidenceRetriever retriever = retriever(List.of(evidence(
                "denied", "source-denied", "workspace-source:source-denied", 9, 6)));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                List.of(retriever), new EvidenceBudgeter(), meterRegistry, new EvidenceScopeGuard());
        RetrievalPlan plan = new RetrievalPlan(
                "fixture-v1", AnswerMode.QA,
                List.of(new RetrievalPlan.Step("fixture", 3, 1, Map.of())),
                new RetrievalPlan.Budget(3, 100, 0, 0));
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query",
                Set.of("source-allowed"), Map.of(), Instant.now());

        assertThatThrownBy(() -> orchestrator.execute(context, plan))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("EVIDENCE_SCOPE_VIOLATION");
        assertThat(meterRegistry.get("noteweave.retrieval.scope_violation")
                .tags("mode", "QA", "channel", "fixture").counter().count()).isEqualTo(1);
    }

    @Test
    void shouldRejectCrossWorkspacePassageEvidenceForNoteMode() {
        EvidenceRetriever retriever = retriever(List.of(evidence(
                "foreign", "source-foreign", "workspace-source:source-foreign", 9, 6)));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        EvidenceOwnershipGuard ownershipGuard = new EvidenceOwnershipGuard(
                (workspaceId, identities) -> Set.of());
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                List.of(retriever), new EvidenceBudgeter(), meterRegistry,
                new EvidenceScopeGuard(), ownershipGuard);
        RetrievalPlan plan = new RetrievalPlan(
                "note-fixture-v1", AnswerMode.NOTE,
                List.of(new RetrievalPlan.Step("fixture", 3, 1, Map.of())),
                new RetrievalPlan.Budget(3, 100, 0, 0));

        assertThatThrownBy(() -> orchestrator.execute(context(), plan))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("EVIDENCE_SCOPE_VIOLATION");
        assertThat(meterRegistry.get("noteweave.retrieval.scope_violation")
                .tags("mode", "NOTE", "channel", "fixture").counter().count())
                .isEqualTo(1);
    }

    @Test
    void shouldRejectEvidenceWhoseAccessScopeDoesNotMatchItsIdentity() {
        EvidenceRetriever retriever = retriever(List.of(evidence(
                "mismatch", "source", "workspace-source:other", 9, 6)));
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(List.of(retriever));
        RetrievalPlan plan = new RetrievalPlan(
                "fixture-v1", AnswerMode.QA,
                List.of(new RetrievalPlan.Step("fixture", 3, 1, Map.of())),
                new RetrievalPlan.Budget(3, 100, 0, 0));

        assertThatThrownBy(() -> orchestrator.execute(context(), plan))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("EVIDENCE_SCOPE_VIOLATION");
    }

    private AnswerContext context() {
        return new AnswerContext("workspace", "conversation", "message", "query", Set.of(), Map.of(), Instant.now());
    }

    private EvidenceRetriever retriever(List<EvidenceBundle.Evidence> evidence) {
        return new EvidenceRetriever() {
            @Override
            public String channel() {
                return "fixture";
            }

            @Override
            public EvidenceRetrievalResult retrieve(
                    AnswerContext context,
                    RetrievalPlan plan,
                    RetrievalPlan.Step step
            ) {
                return EvidenceRetrievalResult.success(evidence);
            }
        };
    }

    private EvidenceBundle.Evidence evidence(String id, double score) {
        return evidence(id, score, id.length());
    }

    private EvidenceBundle.Evidence evidence(String id, double score, int characterCost) {
        return evidence(id, "source", "workspace-source:source", score, characterCost);
    }

    private EvidenceBundle.Evidence evidence(
            String id,
            String sourceId,
            String accessScope,
            double score,
            int characterCost
    ) {
        return new EvidenceBundle.Evidence(
                id, "PASSAGE", sourceId, "snapshot", id, "", "", id, id,
                "chunk:0", score, score, score, accessScope, Instant.now(),
                "fixture", characterCost, Map.of()
        );
    }
}
