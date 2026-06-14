package com.noteweave.personal.claim.service;

import com.noteweave.embedding.service.EmbeddingClient;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.claim.model.Claim;
import com.noteweave.personal.claim.model.ClaimConceptRelation;
import com.noteweave.personal.claim.repository.ClaimConceptRelationRepository;
import com.noteweave.personal.question.repository.ResearchQuestionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClaimIndexService {

    private final SearchIndexClaimSupport searchIndexClaimSupport;
    private final ClaimConceptRelationRepository claimConceptRelationRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final ResearchQuestionRepository researchQuestionRepository;
    private final EmbeddingClient embeddingClient;

    public void syncClaim(Claim claim) {
        if (claim == null || claim.getId() == null || claim.getDeletedAt() != null) {
            return;
        }
        List<String> conceptNames = loadConceptNames(claim.getId());
        List<Float> embedding = loadEmbedding(claim, conceptNames);
        String questionTitle = researchQuestionRepository.findById(claim.getResearchQuestionId())
                .map(question -> question.getTitle() == null ? "" : question.getTitle())
                .orElse("");
        searchIndexClaimSupport.index(new SearchIndexClaimSupport.ClaimIndexDocument(
                searchIndexClaimSupport.esDocId(claim.getId()),
                claim.getSpaceId(),
                claim.getUserId(),
                claim.getResearchProjectId(),
                claim.getResearchQuestionId(),
                questionTitle,
                claim.getId(),
                claim.getStatement(),
                claim.getRationale(),
                claim.getClaimType().name(),
                claim.getStance().name(),
                claim.getConfidence() == null ? 0.5d : claim.getConfidence().doubleValue(),
                claim.getCardStatus().name(),
                conceptNames,
                embedding,
                claim.getUpdatedAt() == null ? null : claim.getUpdatedAt().toString()
        ));
    }

    public void deleteClaim(Long claimId) {
        searchIndexClaimSupport.deleteByClaimId(claimId);
    }

    private List<String> loadConceptNames(Long claimId) {
        List<ClaimConceptRelation> relations = claimConceptRelationRepository.findByClaimId(claimId);
        if (relations.isEmpty()) {
            return List.of();
        }
        Map<Long, String> namesById = conceptCardRepository.findByIdIn(relations.stream()
                        .map(ClaimConceptRelation::getConceptCardId)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(ConceptCard::getId, ConceptCard::getName, (left, right) -> left, LinkedHashMap::new));
        return relations.stream()
                .map(ClaimConceptRelation::getConceptCardId)
                .map(namesById::get)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    private List<Float> loadEmbedding(Claim claim, List<String> conceptNames) {
        List<float[]> vectors;
        try {
            vectors = embeddingClient.embedTexts(List.of(embeddingText(claim, conceptNames)));
        } catch (Exception ex) {
            return List.of();
        }
        if (vectors.isEmpty() || vectors.get(0) == null || vectors.get(0).length == 0) {
            return List.of();
        }
        List<Float> values = new ArrayList<>(vectors.get(0).length);
        for (float value : vectors.get(0)) {
            values.add(value);
        }
        return values;
    }

    private String embeddingText(Claim claim, List<String> conceptNames) {
        StringBuilder builder = new StringBuilder();
        builder.append(claim.getStatement() == null ? "" : claim.getStatement());
        if (claim.getRationale() != null && !claim.getRationale().isBlank()) {
            builder.append('\n').append(claim.getRationale());
        }
        if (!conceptNames.isEmpty()) {
            builder.append("\nConcepts: ").append(String.join(", ", conceptNames));
        }
        return builder.toString();
    }
}
