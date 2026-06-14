package com.noteweave.team.rag.retriever;

import com.noteweave.embedding.service.EmbeddingClient;
import com.noteweave.personal.claim.service.SearchIndexClaimSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClaimRetriever implements Retriever {

    private static final long CLAIM_CHUNK_NAMESPACE = 1_000_000_000_000L;

    private final SearchIndexClaimSupport searchIndexClaimSupport;
    private final EmbeddingClient embeddingClient;
    private final WeightedReciprocalRankFusion fusion;

    @Override
    public String name() {
        return "Claim";
    }

    @Override
    public List<RetrievalHit> retrieve(TeamRetrievalQuery query) {
        if (query == null
                || query.userId() == null
                || query.spaceId() == null
                || query.researchQuestionId() == null
                || query.query() == null
                || query.query().isBlank()) {
            return List.of();
        }
        List<RetrievalHit> keywordHits = mapKeywordHits(searchIndexClaimSupport.search(
                query.userId(),
                query.spaceId(),
                query.query(),
                query.topK(),
                query.researchQuestionId()
        ));
        List<RetrievalHit> vectorHits = mapVectorHits(query);
        List<RetrievalHit> fusedHits;
        if (vectorHits.isEmpty()) {
            fusedHits = keywordHits;
        } else if (keywordHits.isEmpty()) {
            fusedHits = vectorHits;
        } else {
            fusedHits = fusion.fuse(
                    List.of(keywordHits, vectorHits),
                    RrfOptions.builder()
                            .rrfK(60)
                            .topK(query.topK())
                            .weights(Map.of("ClaimKeyword", 1.0d, "ClaimVector", 1.0d))
                            .build()
            );
        }
        List<RetrievalHit> normalized = new ArrayList<>(fusedHits.size());
        for (int index = 0; index < fusedHits.size(); index++) {
            RetrievalHit hit = fusedHits.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (hit.metadata() != null) {
                metadata.putAll(hit.metadata());
            }
            metadata.put("sourceType", "CLAIM");
            normalized.add(hit.toBuilder()
                    .retrieverName(name())
                    .rank(index + 1)
                    .metadata(metadata)
                    .build());
        }
        return normalized;
    }

    private List<RetrievalHit> mapKeywordHits(List<SearchIndexClaimSupport.ClaimSearchHit> hits) {
        List<RetrievalHit> items = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            items.add(toHit("ClaimKeyword", hits.get(index), index + 1));
        }
        return items;
    }

    private List<RetrievalHit> mapVectorHits(TeamRetrievalQuery query) {
        List<float[]> vectors;
        try {
            vectors = embeddingClient.embedTexts(List.of(query.query()));
        } catch (Exception ex) {
            return List.of();
        }
        if (vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length == 0) {
            return List.of();
        }
        List<SearchIndexClaimSupport.ClaimSearchHit> hits = searchIndexClaimSupport.searchByVector(
                query.userId(),
                query.spaceId(),
                vectors.get(0),
                query.topK(),
                query.researchQuestionId()
        );
        List<RetrievalHit> items = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            items.add(toHit("ClaimVector", hits.get(index), index + 1));
        }
        return items;
    }

    private RetrievalHit toHit(String retrieverName, SearchIndexClaimSupport.ClaimSearchHit hit, int rank) {
        return RetrievalHit.builder()
                .retrieverName(retrieverName)
                .chunkId(syntheticChunkId(hit.claimId()))
                .documentId(hit.claimId())
                .knowledgeBaseId(0L)
                .spaceId(null)
                .chunkIndex(0)
                .documentTitle(buildTitle(hit))
                .content(buildContent(hit))
                .score(hit.score())
                .rank(rank)
                .metadata(Map.of(
                        "sourceType", "CLAIM",
                        "sourceId", hit.claimId(),
                        "claimId", hit.claimId(),
                        "researchQuestionId", hit.researchQuestionId(),
                        "claimType", hit.claimType(),
                        "stance", hit.stance(),
                        "confidence", hit.confidence(),
                        "researchQuestionTitle", hit.researchQuestionTitle(),
                        "indexVersion", 0
                ))
                .build();
    }

    private String buildTitle(SearchIndexClaimSupport.ClaimSearchHit hit) {
        String questionTitle = hit.researchQuestionTitle() == null || hit.researchQuestionTitle().isBlank()
                ? "Research question " + hit.researchQuestionId()
                : hit.researchQuestionTitle();
        return "Claim - " + questionTitle;
    }

    private String buildContent(SearchIndexClaimSupport.ClaimSearchHit hit) {
        StringBuilder builder = new StringBuilder();
        builder.append('[').append(hit.claimType()).append('/').append(hit.stance()).append("] ")
                .append(hit.statement());
        if (hit.rationale() != null && !hit.rationale().isBlank()) {
            builder.append("\nRationale: ").append(hit.rationale());
        }
        if (hit.conceptNames() != null && !hit.conceptNames().isEmpty()) {
            builder.append("\nConcepts: ").append(String.join(", ", hit.conceptNames()));
        }
        return builder.toString();
    }

    private long syntheticChunkId(Long claimId) {
        return -(CLAIM_CHUNK_NAMESPACE + claimId);
    }
}
