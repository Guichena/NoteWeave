package com.noteweave.personal.card.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.dto.ArticleCardResponse;
import com.noteweave.personal.card.dto.RelatedConceptResponse;
import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ArticleConceptRelation;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.repository.ArticleCardRepository;
import com.noteweave.personal.card.repository.ArticleConceptRelationRepository;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.compiler.dto.ArticleCardDraft;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.space.model.Space;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ArticleCardService {

    private final ArticleCardRepository articleCardRepository;
    private final ArticleConceptRelationRepository articleConceptRelationRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final PersonalCardCitationService personalCardCitationService;
    private final ResearchProjectService researchProjectService;
    private final PersonalSpaceService personalSpaceService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ArticleCard createOrUpdateFromSource(ResearchProject project, Long sourceId, ArticleCardDraft draft, List<Map<String, Object>> evidenceQuotes) {
        ArticleCard articleCard = articleCardRepository.findBySourceId(sourceId).orElseGet(ArticleCard::new);
        articleCard.setSpaceId(project.getSpaceId());
        articleCard.setResearchProjectId(project.getId());
        articleCard.setSourceId(sourceId);
        articleCard.setTitle(normalizeTitle(draft.title()));
        articleCard.setSummary(normalizeOptional(draft.summary()));
        articleCard.setKeyPointsJson(writeJson(draft.keyPoints()));
        articleCard.setTagsJson(writeJson(draft.tags()));
        articleCard.setEvidenceQuotesJson(writeJson(evidenceQuotes));
        return articleCardRepository.save(articleCard);
    }

    @Transactional(readOnly = true)
    public List<ArticleCardResponse> list(Long userId, Long projectId, String keyword) {
        ResearchProject project = researchProjectService.getRequiredActiveProject(userId, projectId);
        String normalizedKeyword = normalizeOptional(keyword);
        return articleCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(project.getId(), project.getSpaceId()).stream()
                .filter(card -> matches(card, normalizedKeyword))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ArticleCardResponse get(Long userId, Long cardId) {
        return toResponse(getRequiredCard(userId, cardId));
    }

    @Transactional(readOnly = true)
    public ArticleCard getRequiredCard(Long userId, Long cardId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        ArticleCard articleCard = articleCardRepository.findByIdAndSpaceId(cardId, personalSpace.getId())
                .orElseThrow(() -> articleCardRepository.findById(cardId).isPresent()
                        ? new BusinessException(ErrorCode.RESEARCH_PROJECT_ACCESS_DENIED, "No permission to access this article card")
                        : new BusinessException(ErrorCode.ARTICLE_CARD_NOT_FOUND));
        try {
            researchProjectService.getRequiredActiveProject(userId, articleCard.getResearchProjectId());
        } catch (BusinessException ex) {
            throw new BusinessException(ErrorCode.ARTICLE_CARD_NOT_FOUND);
        }
        return articleCard;
    }

    @Transactional
    public void replaceRelatedConcepts(ArticleCard articleCard, List<RelatedConceptResponse> relatedConcepts) {
        articleConceptRelationRepository.deleteByArticleCardId(articleCard.getId());
        for (RelatedConceptResponse relatedConcept : relatedConcepts) {
            ArticleConceptRelation relation = new ArticleConceptRelation();
            relation.setArticleCardId(articleCard.getId());
            relation.setConceptCardId(relatedConcept.conceptCardId());
            relation.setSourceId(articleCard.getSourceId());
            relation.setEvidence(relatedConcept.evidence());
            relation.setRelevanceScore(java.math.BigDecimal.valueOf(relatedConcept.relevanceScore()));
            articleConceptRelationRepository.save(relation);
        }
    }

    public ArticleCardResponse toResponse(ArticleCard articleCard) {
        List<RelatedConceptResponse> relatedConcepts = new ArrayList<>();
        for (ArticleConceptRelation relation : articleConceptRelationRepository.findByArticleCardIdOrderByIdAsc(articleCard.getId())) {
            conceptCardRepository.findById(relation.getConceptCardId()).ifPresent(conceptCard -> relatedConcepts.add(
                    RelatedConceptResponse.builder()
                            .conceptCardId(conceptCard.getId())
                            .name(conceptCard.getName())
                            .relevanceScore(relation.getRelevanceScore().doubleValue())
                            .evidence(relation.getEvidence())
                            .build()
            ));
        }
        return ArticleCardResponse.builder()
                .id(articleCard.getId())
                .spaceId(articleCard.getSpaceId())
                .researchProjectId(articleCard.getResearchProjectId())
                .sourceId(articleCard.getSourceId())
                .title(articleCard.getTitle())
                .summary(articleCard.getSummary())
                .keyPoints(readList(articleCard.getKeyPointsJson()))
                .tags(readList(articleCard.getTagsJson()))
                .evidenceQuotes(readMapList(articleCard.getEvidenceQuotesJson()))
                .citations(personalCardCitationService.listArticleCitations(articleCard.getId()))
                .relatedConcepts(relatedConcepts)
                .createdAt(articleCard.getCreatedAt())
                .updatedAt(articleCard.getUpdatedAt())
                .build();
    }

    private boolean matches(ArticleCard card, String keyword) {
        if (keyword == null) {
            return true;
        }
        String haystack = (card.getTitle() + " " + normalizeOptional(card.getSummary()) + " " + normalizeOptional(card.getTagsJson())).toLowerCase();
        return haystack.contains(keyword.toLowerCase());
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to read article card list json", ex);
        }
    }

    private List<Map<String, Object>> readMapList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException ex) {
            try {
                List<String> legacyQuotes = objectMapper.readValue(json, new TypeReference<List<String>>() {
                });
                return legacyQuotes.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(value -> Map.<String, Object>of("quoteText", value))
                        .toList();
            } catch (JsonProcessingException legacyEx) {
                throw new IllegalStateException("Failed to read article card evidence json", ex);
            }
        }
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to write article card json", ex);
        }
    }

    private String normalizeTitle(String title) {
        String normalized = normalizeOptional(title);
        return normalized == null ? "Untitled source summary" : normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }
}
