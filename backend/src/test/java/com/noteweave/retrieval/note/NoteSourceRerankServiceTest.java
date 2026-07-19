package com.noteweave.retrieval.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.chat.NoteRecallRanker.ScoredCandidate;
import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.retrieval.provider.RerankClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoteSourceRerankServiceTest {
    @Test
    void shouldUseProviderOrderAndPersistRerankScore() {
        RerankClient client = mock(RerankClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.rerank("query", List.of(
                "A\nPDF\nsummary\n[]\n{}\nsample",
                "B\nPDF\nsummary\n[]\n{}\nsample"), 2))
                .thenReturn(new RerankClient.RerankResult(List.of(
                        new RerankClient.Hit(1, 0.91, 1),
                        new RerankClient.Hit(0, 0.42, 2)), "rerank-v1"));
        NoteSourceRerankService service = new NoteSourceRerankService(client);

        var result = service.rerank("query", List.of(candidate("a", "A"), candidate("b", "B")), 2);

        assertThat(result.candidates()).extracting(ScoredCandidate::sourceId).containsExactly("b", "a");
        assertThat(result.candidates().get(0).rerankScore()).isEqualTo(0.91);
        assertThat(result.degraded()).isFalse();
    }

    private ScoredCandidate candidate(String id, String title) {
        CandidateSource source = new CandidateSource(id, title, "PDF", 1, 1, "", "",
                "summary", "[]", "{}", "sample", 10, "", List.of(), List.of(), 0, 0, "", "");
        return new ScoredCandidate(source, 1, 2, 3, 4, 5, 10);
    }
}
