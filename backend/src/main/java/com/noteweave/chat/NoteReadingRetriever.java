package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.noteweave.common.text.LexicalTerms;
import com.noteweave.retrieval.NoteRetrievalStrategyProfile;
import com.noteweave.retrieval.RetrievalChannels;
import com.noteweave.retrieval.lexical.Bm25fScorer;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RerankClient;
import com.noteweave.retrieval.qa.QaHybridSearchPort;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaVectorQuery;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class NoteReadingRetriever {
    private final RetrievalHydrator hydrator;
    private final NoteReadingPlanner planner;
    private final EmbeddingClient embeddingClient;
    private final QaHybridSearchPort chunkSearchPort;
    private final RerankClient rerankClient;

    public NoteReadingRetriever(RetrievalHydrator hydrator, NoteReadingPlanner planner) {
        this(hydrator, planner, null, null, null);
    }

    @Autowired
    public NoteReadingRetriever(
            RetrievalHydrator hydrator,
            NoteReadingPlanner planner,
            EmbeddingClient embeddingClient,
            QaHybridSearchPort chunkSearchPort,
            RerankClient rerankClient
    ) {
        this.hydrator = hydrator;
        this.planner = planner;
        this.embeddingClient = embeddingClient;
        this.chunkSearchPort = chunkSearchPort;
        this.rerankClient = rerankClient;
    }

    public List<ReadingWindow> retrieve(String workspaceId, List<CandidateSource> sources, String query) {
        return retrieveWithDiagnostics(workspaceId, sources, query).windows();
    }

    public ReadingResult retrieveWithDiagnostics(
            String workspaceId,
            List<CandidateSource> sources,
            String query
    ) {
        return retrieveWithDiagnostics(workspaceId, sources, RetrievalChannels.same(query));
    }

    public ReadingResult retrieveWithDiagnostics(
            String workspaceId,
            List<CandidateSource> sources,
            RetrievalChannels channels
    ) {
        RetrievalChannels safeChannels = channels == null ? RetrievalChannels.same("") : channels;
        if (sources == null || sources.isEmpty()) {
            return new ReadingResult(List.of(), false, List.of(), Map.of(
                    "note_window_candidate_count", 0L,
                    "note_window_semantic_hit_count", 0L,
                    "note_window_rerank_count", 0L
            ));
        }
        Map<String, List<ReadingWindow>> windows = hydrator.hydrateNoteWindows(
                workspaceId, sources.stream().map(CandidateSource::sourceId).toList());
        SemanticScoreResult semantic = semanticChunkScores(
                workspaceId, sources, safeChannels.vectorQuery());
        Map<String, Double> semanticByChunk = semantic.scores();
        List<ReadingWindow> selected = new ArrayList<>();
        for (CandidateSource source : sources) {
            List<ReadingWindow> sourceWindows = windows.getOrDefault(source.sourceId(), List.of());
            selected.addAll(planner.planPrescored(
                    fuseWindows(sourceWindows, semanticByChunk, safeChannels.lexicalQuery()), 4));
        }
        // 角色优先级高的排在前面。Rerank 不可用时只保留列表前部，主窗口不能被次要窗口挤掉。
        List<ReadingWindow> candidates = selected.stream().sorted(Comparator
                        .comparingInt((ReadingWindow window) -> planner.rolePriority(window.readRole())).reversed()
                        .thenComparing(Comparator.comparingInt(ReadingWindow::score).reversed())
                        .thenComparing(ReadingWindow::title).thenComparingInt(ReadingWindow::chunkNo)
                        .thenComparingInt(ReadingWindow::windowNo)).limit(24).toList();
        WindowRerankResult reranked = rerankWindows(safeChannels.rerankQuery(), candidates, 8);
        LinkedHashSet<String> degradationReasons = new LinkedHashSet<>();
        degradationReasons.addAll(semantic.degradationReasons());
        degradationReasons.addAll(reranked.degradationReasons());
        return new ReadingResult(
                reranked.windows(),
                !degradationReasons.isEmpty(),
                List.copyOf(degradationReasons),
                Map.of(
                        "note_window_candidate_count", (long) candidates.size(),
                        "note_window_semantic_hit_count", (long) semanticByChunk.size(),
                        "note_window_rerank_count", (long) reranked.rerankCount()
                )
        );
    }

    private List<ReadingWindow> fuseWindows(
            List<ReadingWindow> sourceWindows,
            Map<String, Double> semanticByChunk,
            String lexicalQuery
    ) {
        if (sourceWindows == null || sourceWindows.isEmpty()) {
            return List.of();
        }
        Map<String, Double> bm25 = bm25Scores(sourceWindows, lexicalQuery);
        List<ReadingWindow> lexicalRanked = sourceWindows.stream()
                .filter(window -> bm25.getOrDefault(windowKey(window), 0.0d) > 0.0d)
                .sorted(Comparator.comparingDouble((ReadingWindow window) -> bm25.get(windowKey(window))).reversed()
                        .thenComparingInt(ReadingWindow::chunkNo)
                        .thenComparingInt(ReadingWindow::windowNo))
                .toList();
        List<ReadingWindow> vectorRanked = sourceWindows.stream()
                .filter(window -> semanticByChunk.getOrDefault(window.chunkId(), 0.0d) > 0.0d)
                .sorted(Comparator.comparingDouble((ReadingWindow window) ->
                                semanticByChunk.getOrDefault(window.chunkId(), 0.0d)).reversed()
                        .thenComparingInt(ReadingWindow::chunkNo)
                        .thenComparingInt(ReadingWindow::windowNo))
                .toList();
        Map<String, Double> fused = new HashMap<>();
        addWindowChannel(fused, lexicalRanked, NoteRetrievalStrategyProfile.WINDOW_BM25_WEIGHT);
        addWindowChannel(fused, vectorRanked, NoteRetrievalStrategyProfile.WINDOW_VECTOR_WEIGHT);
        return sourceWindows.stream().map(window -> {
            double semantic = semanticByChunk.getOrDefault(window.chunkId(), 0.0d);
            double value = fused.getOrDefault(windowKey(window), 0.0d);
            int score = NoteRetrievalStrategyProfile.scaled(value);
            return window.withRawScore(semantic).withScore(score);
        }).toList();
    }

    private Map<String, Double> bm25Scores(List<ReadingWindow> windows, String query) {
        Set<String> terms = LexicalTerms.queryTerms(query);
        if (terms.isEmpty()) {
            return Map.of();
        }
        List<Bm25fScorer.Document> documents = new ArrayList<>();
        for (ReadingWindow window : windows) {
            documents.add(new Bm25fScorer.Document(windowKey(window), Map.of(
                    "window", text(window.heading()) + "\n" + text(window.content()) + "\n" + text(window.locationInfo()))));
        }
        Map<String, Double> scores = new HashMap<>();
        for (Bm25fScorer.Hit hit : new Bm25fScorer().score(
                Bm25fScorer.DEFAULT_K1,
                NoteRetrievalStrategyProfile.windowFields(),
                documents,
                terms,
                Map.of(),
                Map.of())) {
            scores.put(hit.id(), hit.score());
        }
        return scores;
    }

    private void addWindowChannel(Map<String, Double> fused, List<ReadingWindow> ranked, double weight) {
        int rank = 0;
        for (ReadingWindow window : ranked) {
            rank++;
            fused.merge(windowKey(window), weight / (NoteRetrievalStrategyProfile.RRF_K + rank), Double::sum);
        }
    }

    private String windowKey(ReadingWindow window) {
        return window.sourceId() + ":" + window.chunkNo() + ":" + window.windowNo();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private SemanticScoreResult semanticChunkScores(
            String workspaceId, List<CandidateSource> sources, String query
    ) {
        if (embeddingClient == null || chunkSearchPort == null || !embeddingClient.isEnabled()) {
            return SemanticScoreResult.unavailable();
        }
        try {
            List<Float> vector = embeddingClient.embedQuery(query).singleVector();
            Set<String> scope = sources.stream().map(CandidateSource::sourceId).collect(java.util.stream.Collectors.toSet());
            Map<String, Double> scores = new HashMap<>();
            for (var hit : chunkSearchPort.vectorRetrieve(
                    new QaVectorQuery(workspaceId, query, vector, scope, 80, 0))) {
                scores.put(hit.chunkId(), hit.score());
            }
            return new SemanticScoreResult(Map.copyOf(scores), List.of());
        } catch (RuntimeException ex) {
            return SemanticScoreResult.unavailable();
        }
    }

    private WindowRerankResult rerankWindows(String query, List<ReadingWindow> candidates, int limit) {
        if (candidates.isEmpty()) return new WindowRerankResult(List.of(), List.of(), 0);
        if (rerankClient == null || !rerankClient.isEnabled()) {
            return WindowRerankResult.unavailable(candidates, limit);
        }
        try {
            var result = rerankClient.rerank(query, candidates.stream()
                    .map(window -> String.join("\n", window.title(), window.heading(), window.content()))
                    .toList(), Math.min(limit, candidates.size()));
            if (result == null || result.hits() == null || result.hits().isEmpty()) {
                return WindowRerankResult.unavailable(candidates, limit);
            }
            Set<Integer> returned = new java.util.HashSet<>();
            for (var hit : result.hits()) {
                if (hit == null || hit.documentIndex() < 0 || hit.documentIndex() >= candidates.size()
                        || !Double.isFinite(hit.score()) || !returned.add(hit.documentIndex())) {
                    return WindowRerankResult.unavailable(candidates, limit);
                }
            }
            List<ReadingWindow> reranked = result.hits().stream()
                    .map(hit -> candidates.get(hit.documentIndex()).withRerankScore(hit.score()))
                    .toList();
            return new WindowRerankResult(reranked, List.of(), reranked.size());
        } catch (RuntimeException ex) {
            return WindowRerankResult.unavailable(candidates, limit);
        }
    }

    public record ReadingResult(
            List<ReadingWindow> windows,
            boolean degraded,
            List<String> degradationReasons,
            Map<String, Long> measurements
    ) {
    }

    private record SemanticScoreResult(Map<String, Double> scores, List<String> degradationReasons) {
        private static SemanticScoreResult unavailable() {
            return new SemanticScoreResult(Map.of(), List.of("NOTE_WINDOW_SEMANTIC_UNAVAILABLE"));
        }
    }

    private record WindowRerankResult(
            List<ReadingWindow> windows,
            List<String> degradationReasons,
            int rerankCount
    ) {
        private static WindowRerankResult unavailable(List<ReadingWindow> candidates, int limit) {
            return new WindowRerankResult(
                    candidates.stream().limit(limit).toList(),
                    List.of("NOTE_WINDOW_RERANK_UNAVAILABLE"),
                    0
            );
        }
    }
}
