package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.retrieval.note.NoteSourceSearchPort;
import com.noteweave.retrieval.note.NoteSourceSearchPort.NoteSourceHit;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NoteRecallRetrieverTest {
    @Test
    void shouldOrchestrateRepositoryJournalRelationAndRankerWithoutChangingRecallShape() {
        NoteRecallRepository repository = mock(NoteRecallRepository.class);
        NoteJournalRetriever journalRetriever = mock(NoteJournalRetriever.class);
        CandidateSource source = new CandidateSource(
                "source", "Spring reference", "MARKDOWN", 1, 1,
                "", "", "", "[]", "{}", "",
                0, "", List.of(), List.of(), 0, 0, "", ""
        );
        when(repository.findLexicalSourceIds(eq("workspace"), anyCollection(), anyInt()))
                .thenReturn(List.of("source"));
        when(repository.findCurrentSourcesByIds(eq("workspace"), anyCollection()))
                .thenReturn(List.of(source));
        when(journalRetriever.sourceSignals(eq("workspace"), anySet()))
                .thenReturn(Map.of());
        when(journalRetriever.retrieve("workspace", "spring")).thenReturn(List.of());
        NoteRecallRetriever retriever = new NoteRecallRetriever(
                repository,
                journalRetriever,
                new NoteRelationGraph(),
                new NoteRecallRanker()
        );

        var plan = retriever.retrieve("workspace", "spring");

        assertThat(plan.candidateSources()).extracting(CandidateSource::sourceId)
                .containsExactly("source");
        assertThat(plan.candidateSources().get(0).recallSignals())
                .contains("coverage-aware-metadata-rank", "source-window-ready");
        assertThat(plan.trace().candidateCount()).isEqualTo(1);
        assertThat(plan.trace().metadataScoreSum()).isPositive();
        assertThat(plan.candidateSources().get(0).matchedFields()).contains("title");
        assertThat(plan.degraded()).isTrue();
        assertThat(plan.degradationReasons())
                .containsExactly(
                        "NOTE_SOURCE_RECALL_UNAVAILABLE",
                        "NOTE_LOCAL_METADATA_FALLBACK",
                        "NOTE_SOURCE_RERANK_UNAVAILABLE"
                );
        assertThat(plan.measurements()).containsEntry("note_local_fallback_used", 1L);
        verify(repository).findLexicalSourceIds(eq("workspace"), anyCollection(), anyInt());
        verify(repository).findCurrentSourcesByIds(eq("workspace"), anyCollection());
        verify(repository, never()).findCurrentSources(any());
        verify(repository, never()).sourceIdsByNote(any());
        verify(repository, never()).sourceIdsByAnsweredTurn(any());
    }

    @Test
    void semanticRecallHitShouldSeedRelationExpansion() {
        NoteRecallRepository repository = mock(NoteRecallRepository.class);
        NoteJournalRetriever journalRetriever = mock(NoteJournalRetriever.class);
        CandidateSource anchor = source("anchor", "Semantic anchor", "[\"shared\"]");
        CandidateSource neighbor = source("neighbor", "Related source", "[\"shared\"]");
        when(repository.findCurrentSourcesByIds(eq("workspace"), anyCollection())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(1);
            return List.of(anchor, neighbor).stream()
                    .filter(source -> ids.contains(source.sourceId()))
                    .toList();
        });
        when(repository.findTagOverlapSourceIds(eq("workspace"), anyCollection(), anyCollection(), anyInt()))
                .thenReturn(List.of("neighbor"));
        when(journalRetriever.sourceSignals(eq("workspace"), anySet()))
                .thenReturn(Map.of());
        when(journalRetriever.retrieve("workspace", "unmatched query")).thenReturn(List.of());
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isEnabled()).thenReturn(true);
        when(embedding.embedQuery("unmatched query")).thenReturn(
                new EmbeddingClient.EmbeddingResult(List.of(List.of(1f)), "embedding", 1, 1));
        NoteSourceSearchPort search = mock(NoteSourceSearchPort.class);
        when(search.semanticRetrieve("workspace", List.of(1f), 80)).thenReturn(List.of(
                new NoteSourceHit("anchor", "snapshot-anchor", "Semantic anchor", "PDF", "",
                        List.of("shared"), "", 1.0d)));
        when(search.metadataRetrieve("workspace", "unmatched query", 80)).thenReturn(List.of());
        NoteRecallRetriever retriever = new NoteRecallRetriever(
                repository, journalRetriever, new NoteRelationGraph(), new NoteRecallRanker(),
                embedding, search, null, null, false);

        var plan = retriever.retrieve("workspace", "unmatched query");

        assertThat(plan.candidateSources()).filteredOn(source -> source.sourceId().equals("neighbor"))
                .singleElement()
                .extracting(CandidateSource::recallSignals)
                .asString()
                .contains("relation-expansion", "tag-overlap");
    }

    @Test
    void enabledProviderWithNoHitsMustNotUseLocalMetadataFallback() {
        NoteRecallRepository repository = mock(NoteRecallRepository.class);
        NoteJournalRetriever journalRetriever = mock(NoteJournalRetriever.class);
        when(journalRetriever.sourceSignals(eq("workspace"), anySet()))
                .thenReturn(Map.of());
        when(journalRetriever.retrieve("workspace", "spring")).thenReturn(List.of());
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isEnabled()).thenReturn(true);
        when(embedding.embedQuery("spring")).thenReturn(
                new EmbeddingClient.EmbeddingResult(List.of(List.of(1f)), "embedding", 1, 1));
        NoteSourceSearchPort search = mock(NoteSourceSearchPort.class);
        when(search.semanticRetrieve("workspace", List.of(1f), 80)).thenReturn(List.of());
        when(search.metadataRetrieve("workspace", "spring", 80)).thenReturn(List.of());
        NoteRecallRetriever retriever = new NoteRecallRetriever(
                repository, journalRetriever, new NoteRelationGraph(), new NoteRecallRanker(),
                embedding, search, null, null, false);

        var plan = retriever.retrieve("workspace", "spring");

        assertThat(plan.candidateSources()).isEmpty();
        assertThat(plan.degradationReasons()).containsExactly("note_no_relevant_candidates");
        assertThat(plan.measurements()).containsEntry("note_local_fallback_used", 0L);
    }

    @Test
    void unavailableProviderMustFailWhenLocalFallbackIsDisabled() {
        NoteRecallRepository repository = mock(NoteRecallRepository.class);
        NoteJournalRetriever journalRetriever = mock(NoteJournalRetriever.class);
        when(journalRetriever.sourceSignals(eq("workspace"), anySet()))
                .thenReturn(Map.of());
        when(journalRetriever.retrieve("workspace", "query")).thenReturn(List.of());
        NoteRecallRetriever retriever = new NoteRecallRetriever(
                repository, journalRetriever, new NoteRelationGraph(), new NoteRecallRanker(),
                null, null, null, null, false);

        assertThatThrownBy(() -> retriever.retrieve("workspace", "query"))
                .isInstanceOfSatisfying(RetrievalProviderException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo("NOTE_SOURCE_RETRIEVAL_PROVIDER_UNAVAILABLE"));
    }

    @Test
    void unionIdsRoundRobinKeepsEveryIdFromASmallTrailingGroup() {
        NoteRecallRepository repository = mock(NoteRecallRepository.class);
        NoteJournalRetriever journalRetriever = mock(NoteJournalRetriever.class);
        List<NoteSourceHit> semanticHits = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            semanticHits.add(new NoteSourceHit(
                    "sem-" + index, "snapshot", "Title", "PDF", "", List.of(), "", 1.0d));
        }
        when(journalRetriever.sourceSignals(eq("workspace"), anySet())).thenReturn(Map.of(
                "journal-0", 5,
                "journal-1", 4,
                "journal-2", 3,
                "journal-3", 2,
                "journal-4", 1));
        when(journalRetriever.retrieve("workspace", "roundrobinquery")).thenReturn(List.of());
        when(repository.findCurrentSourcesByIds(eq("workspace"), anyCollection())).thenReturn(List.of());
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isEnabled()).thenReturn(true);
        when(embedding.embedQuery("roundrobinquery")).thenReturn(
                new EmbeddingClient.EmbeddingResult(List.of(List.of(1f)), "embedding", 1, 1));
        NoteSourceSearchPort search = mock(NoteSourceSearchPort.class);
        when(search.semanticRetrieve(eq("workspace"), eq(List.of(1f)), anyInt())).thenReturn(semanticHits);
        NoteRecallRetriever retriever = new NoteRecallRetriever(
                repository, journalRetriever, new NoteRelationGraph(), new NoteRecallRanker(),
                embedding, search, null, null, false);

        retriever.retrieve("workspace", "roundrobinquery");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captured = ArgumentCaptor.forClass(Collection.class);
        verify(repository).findCurrentSourcesByIds(eq("workspace"), captured.capture());
        assertThat(captured.getValue())
                .hasSize(200)
                .startsWith(
                        "sem-0", "journal-0",
                        "sem-1", "journal-1",
                        "sem-2", "journal-2",
                        "sem-3", "journal-3",
                        "sem-4", "journal-4")
                .contains("journal-0", "journal-1", "journal-2", "journal-3", "journal-4");
    }

    private CandidateSource source(String id, String title, String tagsJson) {
        return new CandidateSource(
                id, title, "PDF", 1, 1, "", "", "", tagsJson, "{}", "",
                0, "", List.of(), List.of(), 0, 0, "", "");
    }
}
