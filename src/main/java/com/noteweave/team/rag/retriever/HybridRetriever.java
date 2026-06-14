package com.noteweave.team.rag.retriever;

import com.noteweave.team.rag.config.RagProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HybridRetriever {

    private final Bm25Retriever bm25Retriever;
    private final VectorRetriever vectorRetriever;
    private final com.noteweave.team.wiki.service.WikiRetriever wikiRetriever;
    private final ClaimRetriever claimRetriever;
    private final WeightedReciprocalRankFusion fusion;
    private final RagProperties ragProperties;

    public HybridRetrievalResult retrieve(TeamRetrievalQuery query, RetrievalMode requestedMode) {
        RetrievalMode mode = requestedMode == null ? ragProperties.retrieval().mode() : requestedMode;
        if (mode == null) {
            mode = RetrievalMode.BM25;
        }

        List<RetrievedChunk> bm25Chunks = bm25Retriever.retrieveChunks(query);
        List<RetrievalHit> bm25Hits = toHits("BM25", bm25Chunks);
        if (mode == RetrievalMode.BM25) {
            return new HybridRetrievalResult(mode, bm25Hits, bm25Hits.size(), 0, bm25Hits.size(), false, traceJson(bm25Hits, List.of(), List.of(), List.of(), bm25Hits));
        }

        List<RetrievalHit> vectorHits = List.of();
        List<RetrievalHit> wikiHits = List.of();
        List<RetrievalHit> claimHits = List.of();
        boolean fallbackUsed = false;
        try {
            vectorHits = vectorRetriever.retrieve(query);
        } catch (Exception ex) {
            fallbackUsed = true;
        }
        if (query.includeWiki()) {
            try {
                wikiHits = wikiRetriever.retrieve(query);
            } catch (Exception ex) {
                wikiHits = List.of();
            }
        }
        try {
            claimHits = claimRetriever.retrieve(query);
        } catch (Exception ex) {
            fallbackUsed = true;
        }
        List<RetrievalHit> fused = vectorHits.isEmpty() && wikiHits.isEmpty() && claimHits.isEmpty()
                ? bm25Hits
                : fusion.fuse(
                        List.of(bm25Hits, vectorHits, wikiHits, claimHits),
                        RrfOptions.builder()
                                .rrfK(ragProperties.retrieval().rrfK())
                                .topK(query.topK())
                                .weights(Map.of(
                                        "BM25", ragProperties.retrieval().bm25Weight(),
                                        "Vector", ragProperties.retrieval().vectorWeight(),
                                        "Wiki", ragProperties.retrieval().wikiWeight(),
                                        "Claim", ragProperties.retrieval().claimWeight()
                                ))
                                .build()
                );
        return new HybridRetrievalResult(mode, fused, bm25Hits.size(), vectorHits.size(), fused.size(), fallbackUsed, traceJson(bm25Hits, vectorHits, wikiHits, claimHits, fused));
    }

    private List<RetrievalHit> toHits(String retrieverName, List<RetrievedChunk> chunks) {
        List<RetrievalHit> hits = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            RetrievedChunk chunk = chunks.get(index);
            hits.add(RetrievalHit.builder()
                    .retrieverName(retrieverName)
                    .chunkId(chunk.chunkId())
                    .documentId(chunk.documentId())
                    .knowledgeBaseId(chunk.knowledgeBaseId())
                    .spaceId(chunk.spaceId())
                    .chunkIndex(chunk.chunkIndex())
                    .documentTitle(chunk.documentTitle())
                    .content(chunk.content())
                    .score(chunk.score())
                    .rank(index + 1)
                    .metadata(Map.of(
                            "indexVersion", chunk.indexVersion(),
                            "sourceType", chunk.sourceType(),
                            "sourceId", chunk.sourceId()
                    ))
                    .build());
        }
        return hits;
    }

    private String traceJson(
            List<RetrievalHit> bm25Hits,
            List<RetrievalHit> vectorHits,
            List<RetrievalHit> wikiHits,
            List<RetrievalHit> claimHits,
            List<RetrievalHit> fusedHits
    ) {
        return """
                {"bm25":%d,"vector":%d,"wiki":%d,"claim":%d,"fusion":%d}
                """.formatted(bm25Hits.size(), vectorHits.size(), wikiHits.size(), claimHits.size(), fusedHits.size());
    }

    public record HybridRetrievalResult(
            RetrievalMode retrievalMode,
            List<RetrievalHit> fusedHits,
            int bm25Count,
            int vectorCount,
            int fusionCount,
            boolean fallbackUsed,
            String traceJson
    ) {
    }
}
