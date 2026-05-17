package com.noteweave.personal.card.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.dto.SynthesisCardResponse;
import com.noteweave.personal.card.dto.SynthesisConceptRelationResponse;
import com.noteweave.personal.card.model.ConceptCard;
import com.noteweave.personal.card.model.SynthesisCard;
import com.noteweave.personal.card.model.SynthesisConceptRelation;
import com.noteweave.personal.card.repository.ConceptCardRepository;
import com.noteweave.personal.card.repository.SynthesisCardRepository;
import com.noteweave.personal.card.repository.SynthesisConceptRelationRepository;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.space.model.Space;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SynthesisCardService {

    private final SynthesisCardRepository synthesisCardRepository;
    private final SynthesisConceptRelationRepository synthesisConceptRelationRepository;
    private final ConceptCardRepository conceptCardRepository;
    private final PersonalCardCitationService personalCardCitationService;
    private final PersonalSpaceService personalSpaceService;
    private final ResearchProjectService researchProjectService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<SynthesisCardResponse> list(Long userId, Long projectId) {
        ResearchProject project = researchProjectService.getRequiredActiveProject(userId, projectId);
        return synthesisCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(project.getId(), project.getSpaceId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SynthesisCardResponse get(Long userId, Long cardId) {
        return toResponse(getRequiredCard(userId, cardId));
    }

    @Transactional(readOnly = true)
    public SynthesisCard getRequiredCard(Long userId, Long cardId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        SynthesisCard synthesisCard = synthesisCardRepository.findByIdAndSpaceId(cardId, personalSpace.getId())
                .orElseThrow(() -> synthesisCardRepository.findById(cardId).isPresent()
                        ? new BusinessException(ErrorCode.RESEARCH_PROJECT_ACCESS_DENIED, "No permission to access this synthesis card")
                        : new BusinessException(ErrorCode.SYNTHESIS_CARD_NOT_FOUND));
        try {
            researchProjectService.getRequiredActiveProject(userId, synthesisCard.getResearchProjectId());
        } catch (BusinessException ex) {
            throw new BusinessException(ErrorCode.SYNTHESIS_CARD_NOT_FOUND);
        }
        return synthesisCard;
    }

    public SynthesisCardResponse toResponse(SynthesisCard synthesisCard) {
        List<SynthesisConceptRelationResponse> conceptRelations = synthesisConceptRelationRepository
                .findBySynthesisCardIdOrderByIdAsc(synthesisCard.getId())
                .stream()
                .map(this::toConceptRelationResponse)
                .toList();
        return SynthesisCardResponse.builder()
                .id(synthesisCard.getId())
                .spaceId(synthesisCard.getSpaceId())
                .researchProjectId(synthesisCard.getResearchProjectId())
                .sourceArtifactId(synthesisCard.getSourceArtifactId())
                .sourceArtifactVersionId(synthesisCard.getSourceArtifactVersionId())
                .title(synthesisCard.getTitle())
                .summary(synthesisCard.getSummary())
                .insights(readStringList(synthesisCard.getInsightsJson()))
                .evidenceQuotes(readMapList(synthesisCard.getEvidenceQuotesJson()))
                .cardStatus(synthesisCard.getCardStatus().name())
                .createdBy(synthesisCard.getCreatedBy())
                .citations(personalCardCitationService.listSynthesisCitations(synthesisCard.getId()))
                .conceptRelations(conceptRelations)
                .createdAt(synthesisCard.getCreatedAt())
                .updatedAt(synthesisCard.getUpdatedAt())
                .build();
    }

    private SynthesisConceptRelationResponse toConceptRelationResponse(SynthesisConceptRelation relation) {
        ConceptCard conceptCard = conceptCardRepository.findById(relation.getConceptCardId()).orElse(null);
        return SynthesisConceptRelationResponse.builder()
                .conceptCardId(relation.getConceptCardId())
                .name(conceptCard == null ? "Concept " + relation.getConceptCardId() : conceptCard.getName())
                .relationType(relation.getRelationType())
                .evidence(relation.getEvidence())
                .build();
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to read synthesis insights json", ex);
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
            throw new IllegalStateException("Failed to read synthesis evidence json", ex);
        }
    }
}
