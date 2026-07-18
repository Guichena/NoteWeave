package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.AnswerStrategyContractValidator;
import com.noteweave.answer.strategy.EvidenceBudgeter;
import com.noteweave.answer.strategy.EvidenceOwnershipGuard;
import com.noteweave.answer.strategy.EvidenceScopeGuard;
import com.noteweave.answer.strategy.RetrievalOrchestrator;
import com.noteweave.chat.QaPassageRetriever.RetrievalResult;
import com.noteweave.chat.QaPassageRetriever.RetrievedChunk;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QaAnswerPipelineContractTest {

    @Test
    void shouldCarryQaPassagesThroughPlanBundlePromptAndCitationBoundary() {
        QaPassageRetriever passageRetriever = mock(QaPassageRetriever.class);
        when(passageRetriever.retrieveWithDiagnostics(
                "workspace", "query", Set.of("source"),
                QaRetrievalStrategyProfile.V2))
                .thenReturn(new RetrievalResult(
                        List.of(chunk("selected", 9), chunk("excluded", 7)),
                        false,
                        List.of(),
                        Map.of("mysql_fallback_used", 0L)
                ));
        QaPassageEvidenceRetriever evidenceRetriever =
                new QaPassageEvidenceRetriever(passageRetriever);
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                List.of(evidenceRetriever),
                new EvidenceBudgeter(),
                new SimpleMeterRegistry(),
                new EvidenceScopeGuard(),
                new EvidenceOwnershipGuard((workspaceId, identities) -> Set.copyOf(identities))
        );
        QaAnswerModeStrategy strategy = new QaAnswerModeStrategy();
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query",
                Set.of("source"), Map.of(),
                Instant.now());

        var plan = strategy.plan(context);
        var bundle = orchestrator.execute(context, plan);
        var prompt = strategy.compose(context, bundle);
        new AnswerStrategyContractValidator().validatePlan(AnswerMode.QA, context, plan);
        new AnswerStrategyContractValidator().validatePromptAndPolicy(
                plan, bundle, prompt, strategy.policy());
        var citations = new EvidenceCitationAssembler(mock(JdbcTemplate.class))
                .assemble(bundle, prompt);

        assertThat(plan.version()).isEqualTo(QaAnswerModeStrategy.PLAN_VERSION);
        assertThat(plan.steps()).extracting(step -> step.channel())
                .containsExactly(QaPassageEvidenceRetriever.CHANNEL);
        assertThat(bundle.evidence()).extracting(evidence -> evidence.evidenceId())
                .containsExactly("passage:selected", "passage:excluded");
        assertThat(bundle.metadata())
                .containsEntry("strategy_profile", "qa-retrieval-v2")
                .containsEntry("relevance_policy", "qa-lexical-sufficiency-v2")
                .containsEntry("selection_policy", "qa-source-diverse-budget-v2");
        assertThat(prompt.referencedEvidenceIds())
                .containsExactlyElementsOf(bundle.evidence().stream()
                        .map(evidence -> evidence.evidenceId())
                        .toList());
        assertThat(citations).extracting(
                        EvidenceCitationAssembler.AssembledCitation::evidenceId)
                .containsExactlyElementsOf(prompt.referencedEvidenceIds());
    }

    private RetrievedChunk chunk(String id, int score) {
        return new RetrievedChunk(
                id, "source", "snapshot", 0, "Title " + id,
                "Content " + id, "chunk:0", "MARKDOWN", "", "",
                score, "fulltext:bm25"
        );
    }
}
