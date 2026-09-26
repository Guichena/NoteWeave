package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class QaPassageRetrieverTest {

    @Test
    void hybridProviderFailureShouldFailClosedWhenMysqlFallbackIsDisabled() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        QaHybridRetriever hybridRetriever = mock(QaHybridRetriever.class);
        when(hybridRetriever.retrieve("workspace", "query", Set.of()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate,
                mock(ChunkSearchPort.class),
                mock(RetrievalHydrator.class),
                hybridRetriever,
                false
        );

        assertThatThrownBy(() -> retriever.retrieve("workspace", "query"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("QA_RETRIEVAL_PROVIDER_UNAVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void primarySearchFailureShouldFailClosedByDefault() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        when(searchPort.search("workspace", "query", 12))
                .thenThrow(new IllegalStateException("provider unavailable"));
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, mock(RetrievalHydrator.class));

        assertThatThrownBy(() -> retriever.retrieve("workspace", "query"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("QA_RETRIEVAL_PROVIDER_UNAVAILABLE");
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void searchHitsShouldApplyExplicitSourceScopeBeforeHydration() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
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
                jdbcTemplate, searchPort, hydrator);

        var result = retriever.retrieve("workspace", "query", Set.of("source-allowed"));

        assertThat(result).extracting(RetrievedChunk::sourceId)
                .containsExactly("source-allowed");
        verify(hydrator).hydratePassageOwnership("workspace", List.of("chunk-allowed"));
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void searchHitsShouldHydrateProvenanceAsOneBatch() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
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
                jdbcTemplate, searchPort, hydrator);

        var result = retriever.retrieve("workspace", "query");

        assertThat(result).hasSize(6);
        assertThat(result.get(0).generatedBy()).isEqualTo("research_agent");
        assertThat(result).extracting(RetrievedChunk::matchReason)
                .allMatch(reason -> reason.contains("fulltext:bm25"));
        verify(hydrator).hydratePassageOwnership("workspace", chunkIds);
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void searchHitsShouldFailClosedWhenChunkOwnershipDoesNotMatchWorkspace() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(searchPort.search("workspace", "query", 12)).thenReturn(List.of(
                hit("chunk-cross-workspace", "source-cross-workspace")
        ));
        when(hydrator.hydratePassageOwnership(
                "workspace", List.of("chunk-cross-workspace"))).thenReturn(Map.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, hydrator);

        var result = retriever.retrieveWithDiagnostics("workspace", "query", Set.of());

        assertThat(result.chunks()).isEmpty();
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons())
                .containsExactly("qa_primary_ownership_rejected");
        assertThat(result.measurements())
                .containsEntry("ownership_rejected_count", 1L)
                .containsEntry("mysql_fallback_used", 0L);
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void searchHitsShouldRejectLexicallyInsufficientEvidenceBeforeHydration() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        when(searchPort.search(
                "workspace", "How does NoteWeave implement quantum banana theorem proof generation?", 12))
                .thenReturn(List.of(new ChunkSearchHit(
                        "chunk-generic", "source-allowed", "snapshot", "0",
                        "README.md", "MARKDOWN",
                        "NoteWeave v2 is a research workspace implemented by Java and Python.", 13.0d)));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, hydrator);

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
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
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
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, hydrator);

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
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
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
                jdbcTemplate, searchPort, hydrator);

        var result = retriever.retrieveWithDiagnostics("workspace", "query", Set.of());

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId)
                .containsExactly("chunk-valid");
        assertThat(result.degraded()).isTrue();
        assertThat(result.degradationReasons())
                .containsExactly("qa_primary_ownership_rejected");
        assertThat(result.measurements())
                .containsEntry("ownership_rejected_count", 2L)
                .containsEntry("mysql_fallback_used", 0L);
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void searchHitsShouldDropContinuationWhenItsLexicalAnchorFailsOwnership() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
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
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, hydrator);

        var result = retriever.retrieveWithDiagnostics("workspace", "alpha beta", Set.of());

        assertThat(result.chunks()).isEmpty();
        assertThat(result.degradationReasons())
                .containsExactly("qa_primary_ownership_rejected");
        assertThat(result.measurements())
                .containsEntry("relevant_primary_hit_count", 0L)
                .containsEntry("ownership_rejected_count", 1L)
                .containsEntry("mysql_fallback_used", 0L)
                .containsEntry("selected_count", 0L);
        org.mockito.Mockito.verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void mysqlFallbackShouldKeepKeywordScoringAndSourceDiversityOrder() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        allowMockedSourceReads(hydrator);
        when(searchPort.search("workspace", "alpha beta", 12)).thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(
                        chunk("chunk-a1", "source-a", "alpha beta"),
                        chunk("chunk-a2", "source-a", "alpha"),
                        chunk("chunk-b1", "source-b", "beta")
                ));
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, hydrator, null, true);

        var retrieval = retriever.retrieveWithDiagnostics(
                "workspace", "alpha beta", Set.of());
        var result = retrieval.chunks();

        assertThat(result).extracting(RetrievedChunk::chunkId)
                .containsExactly("chunk-a1", "chunk-b1", "chunk-a2");
        assertThat(result).extracting(RetrievedChunk::score)
                .containsExactly(27, 12, 15);
        assertThat(result.get(0).matchReason())
                .isEqualTo("chunk-keyword, source-diversity");
        assertThat(result.get(1).matchReason())
                .isEqualTo("chunk-keyword, source-diversity");
        assertThat(result.get(2).matchReason()).isEqualTo("chunk-keyword");
        assertThat(retrieval.degraded()).isTrue();
        assertThat(retrieval.degradationReasons())
                .containsExactly("qa_primary_no_scoped_hits", "qa_mysql_fallback");
        assertThat(retrieval.measurements())
                .containsEntry("strategy_v2_enabled", 1L)
                .containsEntry("mysql_fallback_used", 1L)
                .containsEntry("mysql_candidate_count", 3L)
                .containsEntry("selected_count", 3L);
    }

    @Test
    void mysqlFallbackShouldKeepAdmittedFirstSectionContinuationAtZeroKeywordScore() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        allowMockedSourceReads(hydrator);
        when(searchPort.search("workspace", "alpha beta", 12)).thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(
                        chunk("anchor", "source", 0, "alpha beta"),
                        chunk("continuation", "source", 1, "structural details only")
                ));
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, hydrator, null, true);

        var result = retriever.retrieveWithDiagnostics("workspace", "alpha beta", Set.of());

        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId)
                .containsExactly("anchor", "continuation");
        assertThat(result.chunks()).extracting(RetrievedChunk::score)
                .containsExactly(27, 0);
    }

    @Test
    void mysqlFallbackShouldRecordPrimarySearchFailureWithoutChangingFallbackResult() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        allowMockedSourceReads(hydrator);
        when(searchPort.search("workspace", "alpha", 12))
                .thenThrow(new IllegalStateException(
                        "workspace-sensitive-id alpha chunk-sensitive-id"));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(chunk("chunk-a", "source-a", "alpha")));
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, hydrator, null, true);
        Logger logger = (Logger) LoggerFactory.getLogger(QaPassageRetriever.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            var retrieval = retriever.retrieveWithDiagnostics(
                    "workspace", "alpha", Set.of());

            assertThat(retrieval.chunks()).extracting(RetrievedChunk::chunkId)
                    .containsExactly("chunk-a");
            assertThat(retrieval.degraded()).isTrue();
            assertThat(retrieval.degradationReasons())
                    .containsExactly("qa_primary_search_error", "qa_mysql_fallback");
            assertThat(retrieval.measurements())
                    .containsEntry("primary_hit_count", 0L)
                    .containsEntry("mysql_fallback_used", 1L);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .isEqualTo(
                                "Primary QA retrieval failed; fallback=mysql; "
                                        + "reason=qa_primary_search_error")
                        .doesNotContain(
                                "workspace-sensitive-id",
                                "alpha",
                                "chunk-sensitive-id");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void mysqlFallbackShouldNotReturnInScopeButIrrelevantRecentChunks() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        allowMockedSourceReads(hydrator);
        String query = "How does NoteWeave implement quantum banana theorem proof generation?";
        when(searchPort.search("workspace", query, 12)).thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(chunk(
                        "chunk-generic", "source-allowed",
                        "NoteWeave v2 is a research workspace implemented by Java and Python.")));
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, hydrator, null, true);

        var result = retriever.retrieveWithDiagnostics(
                "workspace", query, Set.of("source-allowed"));

        assertThat(result.chunks()).isEmpty();
        assertThat(result.measurements())
                .containsEntry("mysql_candidate_count", 1L)
                .containsEntry("mysql_relevance_rejected_count", 1L)
                .containsEntry("selected_count", 0L);
    }

    @Test
    void mysqlFallbackShouldRejectUnknownSingleTermInsteadOfReturningRecentChunks() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        allowMockedSourceReads(hydrator);
        when(searchPort.search("workspace", "quantum", 12)).thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(chunk(
                        "chunk-recent", "source-allowed",
                        "Recent workspace notes about ordinary project planning.")));
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, hydrator, null, true);

        var result = retriever.retrieveWithDiagnostics(
                "workspace", "quantum", Set.of("source-allowed"));

        assertThat(result.chunks()).isEmpty();
        assertThat(result.measurements())
                .containsEntry("mysql_candidate_count", 1L)
                .containsEntry("mysql_relevance_rejected_count", 1L)
                .containsEntry("selected_count", 0L);
    }

    @Test
    void mysqlFallbackShouldPushDownAndDefensivelyApplyExplicitSourceScope() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ChunkSearchPort searchPort = mock(ChunkSearchPort.class);
        RetrievalHydrator hydrator = mock(RetrievalHydrator.class);
        allowMockedSourceReads(hydrator);
        when(searchPort.search("workspace", "alpha", 12)).thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(
                        chunk("chunk-allowed", "source-allowed", "alpha"),
                        chunk("chunk-denied", "source-denied", "alpha")
                ));
        QaPassageRetriever retriever = new QaPassageRetriever(
                jdbcTemplate, searchPort, hydrator, null, true);

        var result = retriever.retrieve("workspace", "alpha", Set.of("source-allowed"));

        assertThat(result).extracting(RetrievedChunk::sourceId)
                .containsExactly("source-allowed");
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("and s.id in (?)");
    }

    private ChunkSearchHit hit(String chunkId, String sourceId) {
        return new ChunkSearchHit(
                chunkId, sourceId, "snapshot", "0", "Title", "MARKDOWN", "query content", 1.0d);
    }

    private void allowMockedSourceReads(RetrievalHydrator hydrator) {
        when(hydrator.readableSourceIds(eq("workspace"), any()))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(1);
                    return Set.copyOf(ids);
                });
    }

    private RetrievedChunk chunk(String chunkId, String sourceId, String content) {
        return chunk(chunkId, sourceId, 0, content);
    }

    private RetrievedChunk chunk(String chunkId, String sourceId, int chunkNo, String content) {
        return new RetrievedChunk(
                chunkId, sourceId, "snapshot", chunkNo, "Title", content,
                "chunk:" + chunkNo, "MARKDOWN", "", "", 0, "");
    }

    private PassageOwnership ownership(String chunkId, String sourceId, String snapshotId) {
        return new PassageOwnership(chunkId, sourceId, snapshotId, "", "");
    }
}
