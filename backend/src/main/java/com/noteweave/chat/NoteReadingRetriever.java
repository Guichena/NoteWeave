package com.noteweave.chat;

import com.noteweave.chat.NoteRetrievalService.CandidateSource;
import com.noteweave.chat.NoteRetrievalService.ReadingWindow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RerankClient;
import com.noteweave.retrieval.qa.QaHybridSearchPort;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaVectorQuery;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        if (sources == null || sources.isEmpty()) return List.of();
        Map<String, List<ReadingWindow>> windows = hydrator.hydrateNoteWindows(
                workspaceId, sources.stream().map(CandidateSource::sourceId).toList());
        Map<String, Double> semanticByChunk = semanticChunkScores(workspaceId, sources, query);
        List<ReadingWindow> selected = new ArrayList<>();
        for (CandidateSource source : sources) {
            List<ReadingWindow> sourceWindows = windows.getOrDefault(source.sourceId(), List.of()).stream()
                    .map(window -> window.withRawScore(semanticByChunk.getOrDefault(window.chunkId(), 0.0d))
                            .withScore(window.score()
                                    + (int) Math.round(semanticByChunk.getOrDefault(window.chunkId(), 0.0d) * 100)))
                    .toList();
            selected.addAll(planner.plan(sourceWindows, query, 4));
        }
        List<ReadingWindow> candidates = selected.stream().sorted(Comparator
                        .comparingInt((ReadingWindow window) -> planner.rolePriority(window.readRole()))
                        .thenComparing(Comparator.comparingInt(ReadingWindow::score).reversed())
                        .thenComparing(ReadingWindow::title).thenComparingInt(ReadingWindow::chunkNo)
                        .thenComparingInt(ReadingWindow::windowNo)).limit(24).toList();
        return rerankWindows(query, candidates, 8);
    }

    private Map<String, Double> semanticChunkScores(
            String workspaceId, List<CandidateSource> sources, String query
    ) {
        if (embeddingClient == null || chunkSearchPort == null || !embeddingClient.isEnabled()) return Map.of();
        try {
            List<Float> vector = embeddingClient.embedQuery(query).singleVector();
            Set<String> scope = sources.stream().map(CandidateSource::sourceId).collect(java.util.stream.Collectors.toSet());
            Map<String, Double> scores = new HashMap<>();
            for (var hit : chunkSearchPort.vectorRetrieve(
                    new QaVectorQuery(workspaceId, query, vector, scope, 80, 0))) {
                scores.put(hit.chunkId(), hit.score());
            }
            return scores;
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    private List<ReadingWindow> rerankWindows(String query, List<ReadingWindow> candidates, int limit) {
        if (candidates.isEmpty()) return List.of();
        if (rerankClient == null || !rerankClient.isEnabled()) return candidates.stream().limit(limit).toList();
        try {
            var result = rerankClient.rerank(query, candidates.stream()
                    .map(window -> String.join("\n", window.title(), window.heading(), window.content()))
                    .toList(), Math.min(limit, candidates.size()));
            Map<Integer, Double> scores = new LinkedHashMap<>();
            result.hits().forEach(hit -> scores.put(hit.documentIndex(), hit.score()));
            return result.hits().stream()
                    .filter(hit -> hit.documentIndex() >= 0 && hit.documentIndex() < candidates.size())
                    .map(hit -> candidates.get(hit.documentIndex()).withRerankScore(hit.score()))
                    .toList();
        } catch (RuntimeException ex) {
            return candidates.stream().limit(limit).toList();
        }
    }
}
