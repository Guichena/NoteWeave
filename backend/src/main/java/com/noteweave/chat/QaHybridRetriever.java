package com.noteweave.chat;

import com.noteweave.chat.QaPassageRetriever.RetrievedChunk;
import com.noteweave.chat.RetrievalHydrator.PassageOwnership;
import com.noteweave.retrieval.QaEvidenceSelectionPolicy;
import com.noteweave.retrieval.QaRetrievalStrategyProfile;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.retrieval.qa.QaHybridSearchPort;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaKeywordQuery;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaVectorQuery;
import com.noteweave.retrieval.qa.QaRerankService;
import com.noteweave.retrieval.qa.QaRerankService.RankedHit;
import com.noteweave.retrieval.qa.QaRrfFusionService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class QaHybridRetriever {
    private static final int LOW_RECALL_EXPANSION_THRESHOLD = 3;
    private final EmbeddingClient embeddingClient;
    private final QaHybridSearchPort searchPort;
    private final QaRrfFusionService fusionService;
    private final QaRerankService rerankService;
    private final RetrievalHydrator retrievalHydrator;
    private final QaQueryExpansionService queryExpansionService;

    public QaHybridRetriever(
            EmbeddingClient embeddingClient,
            QaHybridSearchPort searchPort,
            QaRrfFusionService fusionService,
            QaRerankService rerankService,
            RetrievalHydrator retrievalHydrator,
            QaQueryExpansionService queryExpansionService
    ) {
        this.embeddingClient = embeddingClient;
        this.searchPort = searchPort;
        this.fusionService = fusionService;
        this.rerankService = rerankService;
        this.retrievalHydrator = retrievalHydrator;
        this.queryExpansionService = queryExpansionService;
    }

    public HybridResult retrieve(String workspaceId, String query, Set<String> sourceScope) {
        if (!embeddingClient.isEnabled()) {
            throw new RetrievalProviderException("EMBEDDING_PROVIDER_DISABLED",
                    "QA hybrid retrieval requires query embedding");
        }
        EmbeddingClient.EmbeddingResult embedding = embeddingClient.embedQuery(query);
        List<Float> queryVector = embedding.singleVector();
        List<QaHybridSearchPort.QaSearchHit> vectorHits = new ArrayList<>(searchPort.vectorRetrieve(
                new QaVectorQuery(workspaceId, query, queryVector, sourceScope,
                        QaRetrievalStrategyProfile.RECALL_LIMIT,
                        QaRetrievalStrategyProfile.VECTOR_THRESHOLD)));
        List<QaHybridSearchPort.QaSearchHit> keywordHits = new ArrayList<>(searchPort.keywordRetrieve(
                new QaKeywordQuery(workspaceId, query, sourceScope,
                        QaRetrievalStrategyProfile.RECALL_LIMIT,
                        QaRetrievalStrategyProfile.KEYWORD_THRESHOLD)));
        List<String> pipelineDegradations = new ArrayList<>();
        List<QaRrfFusionService.FusedHit> primaryFused = fusionService.fuse(
                vectorHits, keywordHits,
                QaRetrievalStrategyProfile.RRF_K,
                QaRetrievalStrategyProfile.VECTOR_WEIGHT,
                QaRetrievalStrategyProfile.KEYWORD_WEIGHT,
                QaRetrievalStrategyProfile.FUSION_LIMIT);
        List<String> expansions = primaryFused.size() < LOW_RECALL_EXPANSION_THRESHOLD
                ? queryExpansionService.expand(query) : List.of();
        for (String expansion : expansions) {
            try {
                List<Float> expansionVector = embeddingClient.embedQuery(expansion).singleVector();
                mergeDistinct(vectorHits, searchPort.vectorRetrieve(new QaVectorQuery(
                        workspaceId, expansion, expansionVector, sourceScope,
                        QaRetrievalStrategyProfile.RECALL_LIMIT,
                        QaRetrievalStrategyProfile.VECTOR_THRESHOLD)));
                mergeDistinct(keywordHits, searchPort.keywordRetrieve(new QaKeywordQuery(
                        workspaceId, expansion, sourceScope,
                        QaRetrievalStrategyProfile.RECALL_LIMIT,
                        QaRetrievalStrategyProfile.KEYWORD_THRESHOLD)));
            } catch (RuntimeException ex) {
                pipelineDegradations.add("QA_QUERY_EXPANSION_UNAVAILABLE");
            }
        }
        List<QaRrfFusionService.FusedHit> fused = fusionService.fuse(
                vectorHits, keywordHits,
                QaRetrievalStrategyProfile.RRF_K,
                QaRetrievalStrategyProfile.VECTOR_WEIGHT,
                QaRetrievalStrategyProfile.KEYWORD_WEIGHT,
                QaRetrievalStrategyProfile.FUSION_LIMIT);
        QaRerankService.RerankOutcome reranked = rerankService.rerank(
                query, fused, QaRetrievalStrategyProfile.RERANK_LIMIT);
        Map<String, PassageOwnership> ownership = retrievalHydrator.hydratePassageOwnership(
                workspaceId, reranked.hits().stream().map(hit -> hit.hit().chunkId()).toList());
        Map<String, List<RetrievalHydrator.AdjacentPassage>> hydratedAdjacent =
                retrievalHydrator.hydrateAdjacentPassages(
                        workspaceId, reranked.hits().stream().map(hit -> hit.hit().chunkId()).toList());
        Map<String, List<RetrievalHydrator.AdjacentPassage>> adjacent = hydratedAdjacent == null
                ? Map.of() : hydratedAdjacent;
        List<RetrievedChunk> owned = new ArrayList<>();
        int ownershipRejected = 0;
        for (RankedHit ranked : reranked.hits()) {
            QaRrfFusionService.FusedHit hit = ranked.hit();
            PassageOwnership owner = ownership.get(hit.chunkId());
            if (owner == null || !hit.sourceId().equals(owner.sourceId())
                    || !hit.sourceSnapshotId().equals(owner.sourceSnapshotId())) {
                ownershipRejected++;
                continue;
            }
            List<RetrievalHydrator.AdjacentPassage> context = adjacent.getOrDefault(
                    hit.chunkId(), List.of());
            String reason = String.join(",", hit.matchedChannels()) + ",weighted-rrf,rerank";
            if (!context.isEmpty()) {
                reason += ",adjacent-context:" + context.stream()
                        .map(RetrievalHydrator.AdjacentPassage::chunkId)
                        .collect(java.util.stream.Collectors.joining("|"));
            }
            owned.add(new RetrievedChunk(
                    hit.chunkId(), owner.sourceId(), owner.sourceSnapshotId(), hit.chunkNo(),
                    hit.title(), enrich(hit.content(), context), "chunk:" + hit.chunkNo(), hit.sourceType(),
                    owner.generatedBy(), owner.generatedRefId(),
                    (int) Math.round(ranked.rerankScore() * 1000), reason,
                    Math.max(hit.vectorScore(), hit.keywordScore()), hit.rrfScore(), ranked.rerankScore()));
        }
        List<RetrievedChunk> selected = QaEvidenceSelectionPolicy.selectFinalBundle(
                owned, RetrievedChunk::sourceId, RetrievedChunk::chunkId,
                RetrievedChunk::rerankScore, chunk -> chunk.content() == null ? 0 : chunk.content().length());
        Map<String, Long> measurements = new LinkedHashMap<>();
        measurements.put("vector_candidate_count", (long) vectorHits.size());
        measurements.put("keyword_candidate_count", (long) keywordHits.size());
        measurements.put("rrf_candidate_count", (long) fused.size());
        measurements.put("rerank_candidate_count", (long) reranked.hits().size());
        measurements.put("ownership_rejected_count", (long) ownershipRejected);
        measurements.put("selected_count", (long) selected.size());
        measurements.put("mysql_fallback_used", 0L);
        measurements.put("query_expansion_count", (long) expansions.size());
        pipelineDegradations.addAll(reranked.degradationReasons());
        return new HybridResult(selected, !pipelineDegradations.isEmpty(),
                List.copyOf(new java.util.LinkedHashSet<>(pipelineDegradations)), measurements,
                embedding.model(), reranked.model());
    }

    private void mergeDistinct(
            List<QaHybridSearchPort.QaSearchHit> target,
            List<QaHybridSearchPort.QaSearchHit> additions
    ) {
        Set<String> seen = target.stream().map(QaHybridSearchPort.QaSearchHit::chunkId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (additions == null) return;
        additions.stream().filter(hit -> hit != null && seen.add(hit.chunkId())).forEach(target::add);
    }

    private String enrich(String content, List<RetrievalHydrator.AdjacentPassage> adjacent) {
        if (adjacent == null || adjacent.isEmpty()) {
            return content;
        }
        StringBuilder enriched = new StringBuilder();
        adjacent.stream().filter(item -> item.chunkNo() < adjacentAnchor(adjacent))
                .forEach(item -> appendContext(enriched, item));
        if (content != null && !content.isBlank()) {
            if (!enriched.isEmpty()) enriched.append("\n\n");
            enriched.append(content);
        }
        adjacent.stream().filter(item -> item.chunkNo() > adjacentAnchor(adjacent))
                .forEach(item -> appendContext(enriched, item));
        return enriched.toString();
    }

    private int adjacentAnchor(List<RetrievalHydrator.AdjacentPassage> adjacent) {
        return adjacent.get(0).anchorChunkNo();
    }

    private void appendContext(StringBuilder enriched, RetrievalHydrator.AdjacentPassage item) {
        if (!enriched.isEmpty()) enriched.append("\n\n");
        enriched.append("[adjacent chunk ").append(item.chunkNo());
        if (item.heading() != null && !item.heading().isBlank()) {
            enriched.append(" - ").append(item.heading());
        }
        enriched.append("]\n").append(item.content());
    }

    public record HybridResult(
            List<RetrievedChunk> chunks,
            boolean degraded,
            List<String> degradationReasons,
            Map<String, Long> measurements,
            String embeddingModel,
            String rerankModel
    ) {
    }
}
