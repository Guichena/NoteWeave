package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.AnswerMode;
import com.noteweave.answer.strategy.RetrievalPlan;
import com.noteweave.chat.QaPassageRetriever.RetrievedChunk;
import com.noteweave.chat.QaPassageRetriever.RetrievalResult;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QaPassageEvidenceRetrieverTest {

    @Test
    void shouldMapDedicatedRetrieverResultToStablePassageEvidence() {
        QaPassageRetriever passageRetriever = mock(QaPassageRetriever.class);
        when(passageRetriever.retrieveWithDiagnostics(
                "workspace", "query", Set.of("source"),
                QaRetrievalStrategyProfile.V2)).thenReturn(new RetrievalResult(List.of(
                new RetrievedChunk(
                        "chunk", "source", "snapshot", 3, "Report", "content",
                        "chunk:3", "MARKDOWN", "research_agent", "run-1",
                        9, "fulltext:bm25, source-diversity")
        ), false, List.of(), Map.of("mysql_fallback_used", 0L)));
        QaPassageEvidenceRetriever retriever =
                new QaPassageEvidenceRetriever(passageRetriever);

        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query",
                Set.of("source"), Map.of(),
                Instant.now());
        RetrievalPlan plan = new QaAnswerModeStrategy().plan(context);
        RetrievalPlan.Step step = plan.steps().get(0);
        var result = retriever.retrieve(context, plan, step);

        assertThat(result.evidence()).hasSize(1);
        var evidence = result.evidence().get(0);
        assertThat(evidence.evidenceId()).isEqualTo("passage:chunk");
        assertThat(evidence.title()).isEqualTo("Report · Research Report(run-1)");
        assertThat(evidence.rawScore()).isEqualTo(9.0);
        assertThat(evidence.fusedScore()).isEqualTo(9.0);
        assertThat(evidence.freshAt()).isNotNull();
        assertThat(evidence.metadata())
                .containsEntry("raw_title", "Report")
                .containsEntry("chunk_no", "3")
                .containsEntry("freshness_status", "CURRENT_AT_RETRIEVAL")
                .containsEntry("match_reason", "fulltext:bm25, source-diversity");
        assertThat(result.degraded()).isFalse();
        assertThat(result.metadata())
                .containsEntry("strategy_profile", "qa-retrieval-v2")
                .containsEntry("relevance_policy", "qa-lexical-sufficiency-v2")
                .containsEntry("selection_policy", "qa-source-diverse-budget-v2");
        assertThat(result.measurements()).containsEntry("mysql_fallback_used", 0L);
        verify(passageRetriever).retrieveWithDiagnostics(
                "workspace", "query", Set.of("source"),
                QaRetrievalStrategyProfile.V2);
    }

    @Test
    void shouldPropagateMysqlFallbackAsExplicitDegradation() {
        QaPassageRetriever passageRetriever = mock(QaPassageRetriever.class);
        when(passageRetriever.retrieveWithDiagnostics(
                "workspace", "query", Set.of(),
                QaRetrievalStrategyProfile.V2)).thenReturn(new RetrievalResult(
                List.of(),
                true,
                List.of("qa_primary_search_error", "qa_mysql_fallback"),
                Map.of("mysql_fallback_used", 1L)
        ));
        QaPassageEvidenceRetriever retriever =
                new QaPassageEvidenceRetriever(passageRetriever);
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query",
                Set.of(), Map.of(),
                Instant.now());
        RetrievalPlan plan = new QaAnswerModeStrategy().plan(context);

        var result = retriever.retrieve(context, plan, plan.steps().get(0));

        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons())
                .containsExactly("qa_primary_search_error", "qa_mysql_fallback");
        assertThat(result.measurements()).containsEntry("mysql_fallback_used", 1L);
    }

    @Test
    void shouldResolveV2ProfileEvenWhenLegacyWorkspaceAttributeIsFalse() {
        QaPassageRetriever passageRetriever = mock(QaPassageRetriever.class);
        when(passageRetriever.retrieveWithDiagnostics(
                "workspace", "query", Set.of(),
                QaRetrievalStrategyProfile.V2)).thenReturn(new RetrievalResult(
                List.of(), false, List.of(), Map.of("strategy_v2_enabled", 1L)));
        QaPassageEvidenceRetriever retriever =
                new QaPassageEvidenceRetriever(passageRetriever);
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(),
                Map.of("retrieval.strategy.v2", "false"),
                Instant.now());
        RetrievalPlan plan = new QaAnswerModeStrategy().plan(context);

        var result = retriever.retrieve(context, plan, plan.steps().get(0));

        assertThat(result.metadata())
                .containsEntry("strategy_profile", "qa-retrieval-v2")
                .containsEntry("relevance_policy", "qa-lexical-sufficiency-v2")
                .containsEntry("selection_policy", "qa-source-diverse-budget-v2");
        assertThat(result.measurements()).containsEntry("strategy_v2_enabled", 1L);
        verify(passageRetriever).retrieveWithDiagnostics(
                "workspace", "query", Set.of(),
                QaRetrievalStrategyProfile.V2);
    }

    @Test
    void shouldFailClosedBeforeRetrievalWhenPlanTupleIsUnsupported() {
        QaPassageRetriever passageRetriever = mock(QaPassageRetriever.class);
        QaPassageEvidenceRetriever retriever =
                new QaPassageEvidenceRetriever(passageRetriever);
        RetrievalPlan.Step step = new RetrievalPlan.Step(
                QaPassageEvidenceRetriever.CHANNEL, 12, 1, Map.of(
                "workspace_id", "workspace", "snapshot_status", "ACTIVE"));
        RetrievalPlan plan = new RetrievalPlan(
                "qa-passage-v2", AnswerMode.QA, List.of(step),
                new RetrievalPlan.Budget(6, 8_000, 0, 0));
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query",
                Set.of(), Map.of(), Instant.now());

        assertThatThrownBy(() -> retriever.retrieve(context, plan, step))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent QA retrieval strategy tuple");
        org.mockito.Mockito.verifyNoInteractions(passageRetriever);
    }
}
