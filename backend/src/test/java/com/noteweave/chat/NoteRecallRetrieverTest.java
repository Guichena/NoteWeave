package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
                embedding, search, null, null);

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
                embedding, search, null, null);

        var plan = retriever.retrieve("workspace", "spring");

        assertThat(plan.candidateSources()).isEmpty();
        assertThat(plan.degradationReasons()).containsExactly("note_no_relevant_candidates");
    }

    @Test
    void unavailableProviderMustFailClosed() {
        NoteRecallRepository repository = mock(NoteRecallRepository.class);
        NoteJournalRetriever journalRetriever = mock(NoteJournalRetriever.class);
        when(journalRetriever.sourceSignals(eq("workspace"), anySet()))
                .thenReturn(Map.of());
        when(journalRetriever.retrieve("workspace", "query")).thenReturn(List.of());
        NoteRecallRetriever retriever = new NoteRecallRetriever(
                repository, journalRetriever, new NoteRelationGraph(), new NoteRecallRanker(),
                null, null, null, null);

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
                embedding, search, null, null);

        retriever.retrieve("workspace", "roundrobinquery");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captured = ArgumentCaptor.forClass(Collection.class);
        verify(repository, times(1)).findCurrentSourcesByIds(eq("workspace"), captured.capture());
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

    @Test
    void twoCharacterChineseTermsAreNotUsedForExpansion() {
        NoteRecallRepository repository = mock(NoteRecallRepository.class);
        NoteJournalRetriever journalRetriever = mock(NoteJournalRetriever.class);
        CandidateSource anchor = source("anchor", "AnchorTitle", "[\"学习\",\"学习机\"]");
        when(repository.findCurrentSourcesByIds(eq("workspace"), anyCollection())).thenAnswer(invocation -> {
            Collection<?> ids = invocation.getArgument(1);
            return ids.contains("anchor") ? List.of(anchor) : List.of();
        });
        when(journalRetriever.sourceSignals(eq("workspace"), anySet())).thenReturn(Map.of());
        when(journalRetriever.retrieve("workspace", "expansionprobe")).thenReturn(List.of());
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.isEnabled()).thenReturn(true);
        when(embedding.embedQuery("expansionprobe")).thenReturn(
                new EmbeddingClient.EmbeddingResult(List.of(List.of(1f)), "embedding", 1, 1));
        NoteSourceSearchPort search = mock(NoteSourceSearchPort.class);
        when(search.semanticRetrieve("workspace", List.of(1f), 80)).thenReturn(List.of(
                new NoteSourceHit("anchor", "snapshot-anchor", "AnchorTitle", "PDF", "",
                        List.of(), "", 1.0d)));
        when(search.metadataRetrieve("workspace", "expansionprobe", 80)).thenReturn(List.of());
        NoteRecallRetriever retriever = new NoteRecallRetriever(
                repository, journalRetriever, new NoteRelationGraph(), new NoteRecallRanker(),
                embedding, search, null, null);

        retriever.retrieve("workspace", "expansionprobe");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> terms = ArgumentCaptor.forClass(Collection.class);
        verify(repository).findTagOverlapSourceIds(eq("workspace"), terms.capture(), anyCollection(), anyInt());
        assertThat(terms.getValue()).contains("学习机").doesNotContain("学习");
    }

    @Test
    void independentChannelFailuresKeepSuccessfulRecallAndEmptySuccessDoesNotFallback() {
        for (boolean semanticFails : List.of(true, false)) {
            NoteRecallRepository repository = mock(NoteRecallRepository.class);
            NoteJournalRetriever journal = mock(NoteJournalRetriever.class);
            when(repository.findCurrentSourcesByIds(eq("workspace"), anyCollection())).thenAnswer(invocation -> {
                Collection<?> ids = invocation.getArgument(1);
                return ids.contains("source") ? List.of(source("source", "Spring", "[]")) : List.of();
            });
            when(journal.sourceSignals(eq("workspace"), anySet())).thenReturn(Map.of());
            EmbeddingClient embedding = mock(EmbeddingClient.class);
            when(embedding.isEnabled()).thenReturn(true);
            when(embedding.embedQuery(any())).thenReturn(
                    new EmbeddingClient.EmbeddingResult(List.of(List.of(1f)), "stub", 1, 1));
            NoteSourceSearchPort search = mock(NoteSourceSearchPort.class);
            var hit = new NoteSourceHit("source", "snapshot", "Spring", "PDF", "", List.of(), "", 0.9);
            if (semanticFails) {
                when(search.semanticRetrieve(any(), any(), anyInt())).thenThrow(new IllegalStateException());
                when(search.metadataRetrieve("workspace", "spring", 80)).thenReturn(List.of(hit));
            } else {
                when(search.semanticRetrieve(any(), any(), anyInt())).thenReturn(List.of(hit));
                when(search.metadataRetrieve("workspace", "spring", 80)).thenThrow(new IllegalStateException());
            }
            var retriever = new NoteRecallRetriever(repository, journal, new NoteRelationGraph(),
                    new NoteRecallRanker(), embedding, search, null, null);
            var plan = retriever.retrieve("workspace", "spring");
            assertThat(plan.candidateSources()).extracting(CandidateSource::sourceId).containsExactly("source");
            assertThat(plan.degradationReasons()).contains(semanticFails
                    ? "NOTE_SEMANTIC_RECALL_UNAVAILABLE" : "NOTE_METADATA_RECALL_UNAVAILABLE");
        }
    }

    @Test
    void businessFailureMustPropagateUnchanged() {
        var repository = mock(NoteRecallRepository.class);
        var search = mock(NoteSourceSearchPort.class);
        var failure = new com.noteweave.common.BusinessException("ORIGIN_INVALID", "denied",
                org.springframework.http.HttpStatus.FORBIDDEN);
        when(search.metadataRetrieve("workspace", "spring", 80)).thenThrow(failure);
        var retriever = new NoteRecallRetriever(repository, mock(NoteJournalRetriever.class),
                new NoteRelationGraph(), new NoteRecallRanker(), null, search, null, null);
        assertThatThrownBy(() -> retriever.retrieve("workspace", "spring")).isSameAs(failure);
    }

    private CandidateSource source(String id, String title, String tagsJson) {
        return new CandidateSource(
                id, title, "PDF", 1, 1, "", "", "", tagsJson, "{}", "",
                0, "", List.of(), List.of(), 0, 0, "", "");
    }
}
