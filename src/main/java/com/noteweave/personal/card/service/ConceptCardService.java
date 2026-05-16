package com.noteweave.personal.card.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.dto.ConceptCardResponse;
import com.noteweave.personal.card.dto.ConceptRelationResponse;
import com.noteweave.personal.card.dto.MergeConceptCardsRequest;
import com.noteweave.personal.card.dto.RelatedArticleResponse;
import com.noteweave.personal.card.dto.UpdateConceptCardRequest;
import com.noteweave.personal.card.model.ArticleCard;
import com.noteweave.personal.card.model.ArticleConceptRelation;
import com.noteweave.personal.card.model.ConceptAlias;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.ConceptRelation;
import com.noteweave.personal.card.repository.ArticleCardRepository;
import com.noteweave.personal.card.repository.ArticleConceptRelationRepository;
import com.noteweave.personal.card.repository.ConceptAliasRepository;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.card.repository.ConceptRelationRepository;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.compiler.service.ConceptMergeService;
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
public class ConceptCardService {

    private final ArticleCardRepository articleCardRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final ConceptAliasRepository conceptAliasRepository;
    private final ConceptRelationRepository conceptRelationRepository;
    private final ArticleConceptRelationRepository articleConceptRelationRepository;
    private final PersonalCardCitationService personalCardCitationService;
    private final PersonalSpaceService personalSpaceService;
    private final ResearchProjectService researchProjectService;
    private final ConceptMergeService conceptMergeService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<ConceptCardResponse> list(Long userId, Long projectId, String keyword) {
        ResearchProject project = researchProjectService.getRequiredActiveProject(userId, projectId);
        String normalizedKeyword = normalizeOptional(keyword).toLowerCase();
        return conceptCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(project.getId(), project.getSpaceId()).stream()
                .filter(card -> normalizedKeyword.isBlank() || matches(card, normalizedKeyword))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConceptCardResponse get(Long userId, Long cardId) {
        return toResponse(getRequiredCard(userId, cardId));
    }

    @Transactional
    public ConceptCardResponse update(Long userId, Long cardId, UpdateConceptCardRequest request) {
        ConceptCard conceptCard = getRequiredCard(userId, cardId);
        conceptCard.setDefinition(request.getDefinition().trim());
        conceptCard.setExplanation(request.getExplanation().trim());
        conceptCard.setUseCasesJson(writeJson(request.getUseCases()));
        conceptCard.setCommonMisunderstandingsJson(writeJson(request.getCommonMisunderstandings()));
        return toResponse(conceptCardRepository.save(conceptCard));
    }

    @Transactional
    public ConceptCardResponse merge(Long userId, MergeConceptCardsRequest request) {
        ConceptCard target = getRequiredCard(userId, request.getTargetConceptId());
        List<ConceptCard> sources = request.getSourceConceptIds().stream()
                .map(sourceId -> getRequiredCard(userId, sourceId))
                .toList();
        if (sources.stream().anyMatch(source -> !source.getResearchProjectId().equals(target.getResearchProjectId()))) {
            throw new BusinessException(ErrorCode.CONCEPT_MERGE_INVALID, "Concept merge must stay inside one research project");
        }
        conceptMergeService.mergeConcepts(target, sources);
        return toResponse(target);
    }

    @Transactional(readOnly = true)
    public ConceptCard getRequiredCard(Long userId, Long cardId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        ConceptCard conceptCard = conceptCardRepository.findByIdAndSpaceId(cardId, personalSpace.getId())
                .orElseThrow(() -> conceptCardRepository.findById(cardId).isPresent()
                        ? new BusinessException(ErrorCode.RESEARCH_PROJECT_ACCESS_DENIED, "No permission to access this concept card")
                        : new BusinessException(ErrorCode.CONCEPT_CARD_NOT_FOUND));
        try {
            researchProjectService.getRequiredActiveProject(userId, conceptCard.getResearchProjectId());
        } catch (BusinessException ex) {
            throw new BusinessException(ErrorCode.CONCEPT_CARD_NOT_FOUND);
        }
        return conceptCard;
    }

    public ConceptCardResponse toResponse(ConceptCard conceptCard) {
        List<String> aliases = conceptAliasRepository.findByConceptCardIdOrderByAliasAsc(conceptCard.getId()).stream()
                .map(ConceptAlias::getAlias)
                .distinct()
                .toList();

        List<ConceptRelationResponse> relations = new ArrayList<>();
        for (ConceptRelation relation : conceptRelationRepository.findByResearchProjectIdAndSourceConceptIdOrResearchProjectIdAndTargetConceptId(
                conceptCard.getResearchProjectId(),
                conceptCard.getId(),
                conceptCard.getResearchProjectId(),
                conceptCard.getId()
        )) {
            Long otherId = relation.getSourceConceptId().equals(conceptCard.getId()) ? relation.getTargetConceptId() : relation.getSourceConceptId();
            conceptCardRepository.findById(otherId).ifPresent(other -> relations.add(
                    ConceptRelationResponse.builder()
                            .conceptCardId(other.getId())
                            .name(other.getName())
                            .relationType(relation.getRelationType())
                            .description(relation.getDescription())
                            .build()
            ));
        }

        List<RelatedArticleResponse> relatedArticles = articleConceptRelationRepository.findByConceptCardIdOrderByIdAsc(conceptCard.getId()).stream()
                .map(relation -> RelatedArticleResponse.builder()
                        .articleCardId(relation.getArticleCardId())
                        .sourceId(relation.getSourceId())
                        .title(resolveArticleTitle(relation))
                        .relevanceScore(relation.getRelevanceScore().doubleValue())
                        .evidence(relation.getEvidence())
                        .build())
                .toList();

        return ConceptCardResponse.builder()
                .id(conceptCard.getId())
                .spaceId(conceptCard.getSpaceId())
                .researchProjectId(conceptCard.getResearchProjectId())
                .name(conceptCard.getName())
                .normalizedName(conceptCard.getNormalizedName())
                .definition(conceptCard.getDefinition())
                .explanation(conceptCard.getExplanation())
                .useCases(readList(conceptCard.getUseCasesJson()))
                .commonMisunderstandings(readList(conceptCard.getCommonMisunderstandingsJson()))
                .evidenceQuotes(readMapList(conceptCard.getEvidenceQuotesJson()))
                .confidence(conceptCard.getConfidence().doubleValue())
                .aliases(aliases)
                .citations(personalCardCitationService.listConceptCitations(conceptCard.getId()))
                .relations(relations)
                .relatedArticles(relatedArticles)
                .createdAt(conceptCard.getCreatedAt())
                .updatedAt(conceptCard.getUpdatedAt())
                .build();
    }

    private boolean matches(ConceptCard card, String keyword) {
        if (card.getName() != null && card.getName().toLowerCase().contains(keyword)) {
            return true;
        }
        if (card.getDefinition() != null && card.getDefinition().toLowerCase().contains(keyword)) {
            return true;
        }
        return conceptAliasRepository.findByConceptCardIdOrderByAliasAsc(card.getId()).stream()
                .map(ConceptAlias::getAlias)
                .anyMatch(alias -> alias.toLowerCase().contains(keyword));
    }

    private String resolveArticleTitle(ArticleConceptRelation relation) {
        return articleCardRepository.findById(relation.getArticleCardId())
                .map(ArticleCard::getTitle)
                .filter(title -> title != null && !title.isBlank())
                .orElse("Article " + relation.getArticleCardId());
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to read concept card list json", ex);
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
            throw new IllegalStateException("Failed to read concept card evidence json", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to write concept card json", ex);
        }
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }
}
