package com.noteweave.retrieval.qa;

import com.noteweave.retrieval.qa.QaHybridSearchPort.QaSearchHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class QaRrfFusionService {

    public List<FusedHit> fuse(
            List<QaSearchHit> vectorHits,
            List<QaSearchHit> keywordHits,
            int rrfK,
            double vectorWeight,
            double keywordWeight,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }
        int effectiveK = Math.max(1, rrfK);
        double effectiveVectorWeight = Math.max(0.0d, vectorWeight);
        double effectiveKeywordWeight = Math.max(0.0d, keywordWeight);
        Map<String, MutableFusedHit> byChunk = new LinkedHashMap<>();
        merge(byChunk, safe(vectorHits), Channel.VECTOR, effectiveK, effectiveVectorWeight);
        merge(byChunk, safe(keywordHits), Channel.KEYWORD, effectiveK, effectiveKeywordWeight);
        return byChunk.values().stream()
                .map(MutableFusedHit::freeze)
                .sorted(Comparator.comparingDouble(FusedHit::rrfScore).reversed()
                        .thenComparing(FusedHit::chunkId))
                .limit(limit)
                .toList();
    }

    private void merge(
            Map<String, MutableFusedHit> byChunk,
            List<QaSearchHit> hits,
            Channel channel,
            int rrfK,
            double weight
    ) {
        for (int index = 0; index < hits.size(); index++) {
            QaSearchHit hit = hits.get(index);
            if (hit == null || hit.chunkId() == null || hit.chunkId().isBlank()) {
                continue;
            }
            int rank = index + 1;
            MutableFusedHit fused = byChunk.computeIfAbsent(
                    hit.chunkId(), ignored -> new MutableFusedHit(hit));
            fused.record(channel, rank, hit.score(), weight / (rrfK + rank));
        }
    }

    private List<QaSearchHit> safe(List<QaSearchHit> hits) {
        return hits == null ? List.of() : hits;
    }

    private enum Channel {
        VECTOR,
        KEYWORD
    }

    private static final class MutableFusedHit {
        private final QaSearchHit hit;
        private int vectorRank;
        private int keywordRank;
        private double vectorScore;
        private double keywordScore;
        private double rrfScore;
        private final List<String> matchedChannels = new ArrayList<>();

        private MutableFusedHit(QaSearchHit hit) {
            this.hit = hit;
        }

        private void record(Channel channel, int rank, double rawScore, double contribution) {
            if (channel == Channel.VECTOR && vectorRank == 0) {
                vectorRank = rank;
                vectorScore = rawScore;
                matchedChannels.add("vector");
                rrfScore += contribution;
            } else if (channel == Channel.KEYWORD && keywordRank == 0) {
                keywordRank = rank;
                keywordScore = rawScore;
                matchedChannels.add("keyword");
                rrfScore += contribution;
            }
        }

        private FusedHit freeze() {
            return new FusedHit(
                    hit.chunkId(), hit.sourceId(), hit.sourceSnapshotId(), hit.chunkNo(),
                    hit.title(), hit.heading(), hit.sourceType(), hit.content(),
                    vectorRank, keywordRank, vectorScore, keywordScore, rrfScore,
                    List.copyOf(matchedChannels));
        }
    }

    public record FusedHit(
            String chunkId,
            String sourceId,
            String sourceSnapshotId,
            int chunkNo,
            String title,
            String heading,
            String sourceType,
            String content,
            int vectorRank,
            int keywordRank,
            double vectorScore,
            double keywordScore,
            double rrfScore,
            List<String> matchedChannels
    ) {
        public FusedHit {
            matchedChannels = matchedChannels == null ? List.of() : List.copyOf(matchedChannels);
        }
    }
}
