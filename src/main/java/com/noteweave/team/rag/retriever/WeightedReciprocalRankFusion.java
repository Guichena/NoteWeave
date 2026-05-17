package com.noteweave.team.rag.retriever;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WeightedReciprocalRankFusion {

    public List<RetrievalHit> fuse(List<List<RetrievalHit>> hitLists, RrfOptions options) {
        if (hitLists == null || hitLists.isEmpty()) {
            return List.of();
        }
        Map<Long, Aggregate> aggregates = new LinkedHashMap<>();
        int rrfK = Math.max(1, options == null ? 60 : options.rrfK());
        int topK = Math.max(1, options == null ? 10 : options.topK());
        Map<String, Double> weights = options == null || options.weights() == null ? Map.of() : options.weights();

        for (List<RetrievalHit> hits : hitLists) {
            if (hits == null || hits.isEmpty()) {
                continue;
            }
            for (int index = 0; index < hits.size(); index++) {
                RetrievalHit hit = hits.get(index);
                if (hit == null || hit.chunkId() == null) {
                    continue;
                }
                int rank = hit.rank() == null || hit.rank() <= 0 ? index + 1 : hit.rank();
                double weight = weights.getOrDefault(hit.retrieverName(), 1.0d);
                double contribution = weight * (1.0d / (rrfK + rank));
                Aggregate aggregate = aggregates.computeIfAbsent(hit.chunkId(), ignored -> new Aggregate(hit));
                aggregate.score += contribution;
                aggregate.breakdown.put(hit.retrieverName(), contribution);
            }
        }

        return aggregates.values().stream()
                .sorted(Comparator.comparingDouble(Aggregate::score).reversed()
                        .thenComparing(aggregate -> aggregate.prototype.chunkId()))
                .limit(topK)
                .map(aggregate -> aggregate.prototype.toBuilder()
                        .score(aggregate.score)
                        .rank(null)
                        .metadata(mergedMetadata(aggregate))
                        .build())
                .toList();
    }

    private Map<String, Object> mergedMetadata(Aggregate aggregate) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (aggregate.prototype.metadata() != null) {
            metadata.putAll(aggregate.prototype.metadata());
        }
        metadata.put("rrfScore", aggregate.score);
        metadata.put("rrfBreakdown", new LinkedHashMap<>(aggregate.breakdown));
        return metadata;
    }

    private static final class Aggregate {
        private final RetrievalHit prototype;
        private final Map<String, Double> breakdown = new LinkedHashMap<>();
        private double score;

        private Aggregate(RetrievalHit prototype) {
            this.prototype = prototype;
        }

        private double score() {
            return score;
        }
    }
}
