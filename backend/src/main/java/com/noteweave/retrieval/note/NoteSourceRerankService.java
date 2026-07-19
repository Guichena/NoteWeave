package com.noteweave.retrieval.note;

import com.noteweave.chat.NoteRecallRanker.ScoredCandidate;
import com.noteweave.retrieval.provider.RerankClient;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class NoteSourceRerankService {
    private final RerankClient rerankClient;

    public NoteSourceRerankService(RerankClient rerankClient) {
        this.rerankClient = rerankClient;
    }

    public Outcome rerank(String query, List<ScoredCandidate> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) return new Outcome(List.of(), false, List.of(), "");
        int limit = Math.min(Math.max(1, topN), candidates.size());
        if (!rerankClient.isEnabled()) {
            return new Outcome(candidates.stream().limit(limit).toList(), true,
                    List.of("NOTE_SOURCE_RERANK_UNAVAILABLE"), "");
        }
        try {
            RerankClient.RerankResult result = rerankClient.rerank(query,
                    candidates.stream().map(this::document).toList(), limit);
            List<ScoredCandidate> ranked = result.hits().stream()
                    .filter(hit -> hit.documentIndex() >= 0 && hit.documentIndex() < candidates.size())
                    .map(hit -> candidates.get(hit.documentIndex()).withRerankScore(hit.score()))
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            java.util.Set<Integer> returned = result.hits().stream()
                    .map(RerankClient.Hit::documentIndex).collect(java.util.stream.Collectors.toSet());
            for (int index = 0; index < candidates.size(); index++) {
                if (!returned.contains(index)) ranked.add(candidates.get(index));
            }
            return new Outcome(ranked, false, List.of(), result.model());
        } catch (RuntimeException ex) {
            return new Outcome(candidates.stream().limit(limit).toList(), true,
                    List.of("NOTE_SOURCE_RERANK_UNAVAILABLE"), "");
        }
    }

    private String document(ScoredCandidate candidate) {
        var source = candidate.source();
        return String.join("\n", source.title(), source.sourceType(), source.summary(),
                source.tagsJson(), source.metadataJson(), source.sampleText());
    }

    public record Outcome(List<ScoredCandidate> candidates, boolean degraded,
                          List<String> degradationReasons, String model) {
    }
}
