package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.chat.QaPassageRetriever.RetrievedChunk;
import com.noteweave.chat.RetrievalHydrator.PassageOwnership;
import com.noteweave.common.BusinessException;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import com.noteweave.search.ChunkSearchHit;
import com.noteweave.search.ChunkSearchPort;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QaPassageRetrieverTest {

    @Test
    void hybridProviderFailureShouldFailClosed() {
        QaHybridRetriever hybridRetriever = mock(QaHybridRetriever.class);
        when(hybridRetriever.retrieve("workspace", "query", Set.of()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        QaPassageRetriever retriever = new QaPassageRetriever(
                mock(ChunkSearchPort.class),
                mock(RetrievalHydrator.class),
                hybridRetriever
        );

        assertThatThrownBy(() -> retriever.retrieve("workspace", "query"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("QA_RETRIEVAL_PROVIDER_UNAVAILABLE");
    }

    @Test
    void primarySearchFailureShouldFailClosedByDefault() {
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        when(searchPort.search("workspace", "query", 12))
                .thenThrow(new IllegalStateException("provider unavailable"));
        QaPassageRetriever retriever = new QaPassageRetriever(
                searchPort, mock(RetrievalHydrator.class));

        assertThatThrownBy(() -> retriever.retrieve("workspace", "query"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("QA_RETRIEVAL_PROVIDER_UNAVAILABLE");
    }

    @Test
    void searchHitsShouldApplyExplicitSourceScopeBeforeHydration() {
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(searchPort.search("workspace", "query", 12)).thenReturn(List.of(
                hit("chunk-denied", "source-denied"),
                hit("chunk-allowed", "source-allowed")
        ));
        when(hydrator.hydratePassageOwnership("workspace", List.of("chunk-allowed")))
                .thenReturn(Map.of(
                        "chunk-allowed",
                        ownership("chunk-allowed", "source-allowed", "snapshot")));
        QaPassageRetriever retriever = new QaPassageRetriever(
                searchPort, hydrator);

        var result = retriever.retrieve("workspace", "query", Set.of("source-allowed"));

        assertThat(result).extracting(RetrievedChunk::sourceId)
                .containsExactly("source-allowed");
        verify(hydrator).hydratePassageOwnership("workspace", List.of("chunk-allowed"));
    }

    @Test
    void searchHitsShouldHydrateProvenanceAsOneBatch() {
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        List<ChunkSearchHit> hits = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> new ChunkSearchHit(
                        "chunk-" + index,
                        "source-" + index,
                        "snapshot-" + index,
                        Integer.toString(index),
                        "Title " + index,
                        "MARKDOWN",
                        "query content",
                        1.0
                ))
                .toList();
        List<String> chunkIds = hits.stream().map(ChunkSearchHit::chunkId).toList();
        when(searchPort.search("workspace", "query", 12)).thenReturn(hits);
        Map<String, PassageOwnership> ownership = java.util.stream.IntStream.range(0, 100)
                .boxed()
                .collect(java.util.stream.Collectors.toMap(
                        index -> "chunk-" + index,
                        index -> new PassageOwnership(
                                "chunk-" + index,
                                "source-" + index,
                                "snapshot-" + index,
                                index == 0 ? "research_agent" : "",
                                index == 0 ? "run-1" : "")
                ));
        when(hydrator.hydratePassageOwnership("workspace", chunkIds))
                .thenReturn(ownership);
        QaPassageRetriever retriever = new QaPassageRetriever(
                searchPort, hydrator);

        var result = retriever.retrieve("workspace", "query");

        assertThat(result).hasSize(6);
        assertThat(result.get(0).generatedBy()).isEqualTo("research_agent");
        assertThat(result).extracting(RetrievedChunk::matchReason)
                .allMatch(reason -> reason.contains("fulltext:bm25"));
        verify(hydrator).hydratePassageOwnership("workspace", chunkIds);
    }

    @Test
    void searchHitsShouldFailClosedWhenChunkOwnershipDoesNotMatchWorkspace() {
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(searchPort.search("workspace", "query", 12)).thenReturn(List.of(
                hit("chunk-cross-workspace", "source-cross-workspace")
        ));
        when(hydrator.hydratePassageOwnership(
                "workspace", List.of("chunk-cross-workspace"))).thenReturn(Map.of());
        QaPassageRetriever retriever = new QaPassageRetriever(
                searchPort, hydrator);

        var result = retriever.retrieveWithDiagnostics("workspace", "query", Set.of());

        assertThat(result.chunks()).isEmpty();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons())
                .containsExactly("qa_primary_ownership_rejected");
        assertThat(result.measurements())
                .containsEntry("ownership_rejected_count", 1L)
                .containsEntry("mysql_fallback_used", 0L);
    }

    @Test
    void searchHitsShouldRejectLexicallyInsufficientEvidenceBeforeHydration() {
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(searchPort.search(
                "workspace", "How does NoteWeave implement quantum banana theorem proof generation?", 12))
                .thenReturn(List.of(new ChunkSearchHit(
                        "chunk-generic", "source-allowed", "snapshot", "0",
                        "README.md", "MARKDOWN",
                        "NoteWeave v2 is a research workspace implemented by Java and Python.", 13.0d)));
        QaPassageRetriever retriever = new QaPassageRetriever(
                searchPort, hydrator);

        var result = retriever.retrieveWithDiagnostics(
                "workspace",
                "How does NoteWeave implement quantum banana theorem proof generation?",
                Set.of("source-allowed"));

        assertThat(result.chunks()).isEmpty();
        assertThat(result.measurements())
                .containsEntry("scoped_primary_hit_count", 1L)
                .containsEntry("relevant_primary_hit_count", 0L)
                .containsEntry("relevance_rejected_count", 1L)
                .containsEntry("selected_count", 0L);
        org.mockito.Mockito.verifyNoInteractions(hydrator);
    }

    @Test
    void searchHitsShouldExecuteV2RelevanceAndRecordProfileMeasurement() {
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        String query = "请总结 ResearchReportCitationToken 的含义";
        when(searchPort.search("workspace", query, 12)).thenReturn(List.of(
                new ChunkSearchHit(
                        "chunk-token", "source", "snapshot", "0",
                        "Report", "MARKDOWN",
                        "ResearchReportCitationToken is present in the saved report output.",
                        9.0d)));
        when(hydrator.hydratePassageOwnership("workspace", List.of("chunk-token")))
                .thenReturn(Map.of(
                        "chunk-token", ownership("chunk-token", "source", "snapshot")));
        QaPassageRetriever retriever = new QaPassageRetriever(
                searchPort, hydrator);

        var v2 = retriever.retrieveWithDiagnostics(
                "workspace", query, Set.of(), QaRetrievalStrategyProfile.V2);

        assertThat(v2.chunks()).extracting(RetrievedChunk::chunkId)
                .containsExactly("chunk-token");
        assertThat(v2.measurements())
                .containsEntry("strategy_v2_enabled", 1L)
                .containsEntry("relevant_primary_hit_count", 1L);
    }

    @Test
    void searchHitsShouldRejectMismatchedSourceOrSnapshotOwnership() {
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(searchPort.search("workspace", "query", 12)).thenReturn(List.of(
                hit("chunk-source-mismatch", "source-hit"),
                hit("chunk-snapshot-mismatch", "source-valid"),
                hit("chunk-valid", "source-valid")
        ));
        when(hydrator.hydratePassageOwnership("workspace", List.of(
                "chunk-source-mismatch", "chunk-snapshot-mismatch", "chunk-valid")))
                .thenReturn(Map.of(
                        "chunk-source-mismatch",
                        ownership("chunk-source-mismatch", "source-owned", "snapshot"),
                        "chunk-snapshot-mismatch",
                        ownership("chunk-snapshot-mismatch", "source-valid", "other-snapshot"),
                        "chunk-valid",
                        ownership("chunk-valid", "source-valid", "snapshot")
                ));
        QaPassageRetriever retriever = new QaPassageRetriever(
                searchPort, hydrator);

        var result = retriever.retrieveWithDiagnostics("workspace", "query", Set.of());

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId)
                .containsExactly("chunk-valid");
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons())
                .containsExactly("qa_primary_ownership_rejected");
        assertThat(result.measurements())
                .containsEntry("ownership_rejected_count", 2L)
                .containsEntry("mysql_fallback_used", 0L);
    }

    @Test
    void searchHitsShouldDropContinuationWhenItsLexicalAnchorFailsOwnership() {
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(searchPort.search("workspace", "alpha beta", 12)).thenReturn(List.of(
                new ChunkSearchHit(
                        "anchor", "source", "snapshot", "0", "Title", "MARKDOWN",
                        "alpha beta", 10.0d),
                new ChunkSearchHit(
                        "continuation", "source", "snapshot", "1", "Title", "MARKDOWN",
                        "structural details only", 9.0d)
        ));
        when(hydrator.hydratePassageOwnership(
                "workspace", List.of("anchor", "continuation")))
                .thenReturn(Map.of(
                        "continuation", ownership("continuation", "source", "snapshot")));
        QaPassageRetriever retriever = new QaPassageRetriever(
                searchPort, hydrator);

        var result = retriever.retrieveWithDiagnostics("workspace", "alpha beta", Set.of());

        assertThat(result.chunks()).isEmpty();
        assertThat(result.degradationReasons())
                .containsExactly("qa_primary_ownership_rejected");
        assertThat(result.measurements())
                .containsEntry("relevant_primary_hit_count", 0L)
                .containsEntry("ownership_rejected_count", 1L)
                .containsEntry("mysql_fallback_used", 0L)
                .containsEntry("selected_count", 0L);
    }

    private ChunkSearchHit hit(String chunkId, String sourceId) {
        return new ChunkSearchHit(
                chunkId, sourceId, "snapshot", "0", "Title", "MARKDOWN", "query content", 1.0d);
    }

    private PassageOwnership ownership(String chunkId, String sourceId, String snapshotId) {
        return new PassageOwnership(chunkId, sourceId, snapshotId, "", "");
    }
}
