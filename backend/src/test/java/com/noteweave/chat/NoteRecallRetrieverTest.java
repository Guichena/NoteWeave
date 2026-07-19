package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
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
}
