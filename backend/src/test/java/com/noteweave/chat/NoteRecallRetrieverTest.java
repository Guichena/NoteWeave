package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.retrieval.note.NoteSourceSearchPort;
import com.noteweave.retrieval.note.NoteSourceSearchPort.NoteSourceHit;
import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
        when(repository.findCurrentSources("workspace")).thenReturn(List.of(source));
        when(repository.sourceIdsByNote("workspace")).thenReturn(Map.of());
        when(repository.sourceIdsByAnsweredTurn("workspace")).thenReturn(Map.of());
        when(journalRetriever.sourceSignals(org.mockito.ArgumentMatchers.eq("workspace"), anySet()))
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
        assertThat(plan.trace().metadataScoreSum()).isEqualTo(27);
        verify(repository).findCurrentSources("workspace");
        verify(repository).sourceIdsByNote("workspace");
        verify(repository).sourceIdsByAnsweredTurn("workspace");
    }

    @Test
    void semanticRecallHitShouldSeedRelationExpansion() {
        NoteRecallRepository repository = mock(NoteRecallRepository.class);
        NoteJournalRetriever journalRetriever = mock(NoteJournalRetriever.class);
        CandidateSource anchor = source("anchor", "Semantic anchor", "[\"shared\"]");
        CandidateSource neighbor = source("neighbor", "Related source", "[\"shared\"]");
        when(repository.findCurrentSources("workspace")).thenReturn(List.of(anchor, neighbor));
        when(repository.sourceIdsByNote("workspace")).thenReturn(Map.of());
        when(repository.sourceIdsByAnsweredTurn("workspace")).thenReturn(Map.of());
        when(journalRetriever.sourceSignals(org.mockito.ArgumentMatchers.eq("workspace"), anySet()))
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

    private CandidateSource source(String id, String title, String tagsJson) {
        return new CandidateSource(
                id, title, "PDF", 1, 1, "", "", "", tagsJson, "{}", "",
                0, "", List.of(), List.of(), 0, 0, "", "");
    }
}
