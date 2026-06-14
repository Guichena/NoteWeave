package com.noteweave.personal.entity.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.card.model.PersonalCardStatus;
import com.noteweave.personal.card.service.ConceptCardService;
import com.noteweave.personal.claim.service.ClaimService;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.entity.dto.CreateEntityCardRequest;
import com.noteweave.personal.entity.dto.EntityCardResponse;
import com.noteweave.personal.entity.dto.LinkEntityRelationRequest;
import com.noteweave.personal.entity.dto.UpdateEntityCardRequest;
import com.noteweave.personal.entity.model.ClaimEntityRelation;
import com.noteweave.personal.entity.model.ConceptEntityRelation;
import com.noteweave.personal.entity.model.EntityCard;
import com.noteweave.personal.entity.repository.ClaimEntityRelationRepository;
import com.noteweave.personal.entity.repository.ConceptEntityRelationRepository;
import com.noteweave.personal.entity.repository.EntityCardRepository;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.space.model.Space;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EntityCardService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final EntityCardRepository entityCardRepository;
    private final ConceptEntityRelationRepository conceptEntityRelationRepository;
    private final ClaimEntityRelationRepository claimEntityRelationRepository;
    private final ResearchProjectService researchProjectService;
    private final PersonalSpaceService personalSpaceService;
    private final ConceptCardService conceptCardService;
    private final ClaimService claimService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<EntityCardResponse> list(Long userId, Long researchProjectId) {
        ResearchProject project = researchProjectService.getRequiredActiveProject(userId, researchProjectId);
        List<EntityCard> cards = new ArrayList<>();
        cards.addAll(entityCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(project.getId(), project.getSpaceId()));
        cards.addAll(entityCardRepository.findBySpaceIdAndResearchProjectIdIsNullOrderByUpdatedAtDesc(project.getSpaceId()));
        return cards.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public EntityCardResponse get(Long userId, Long entityCardId) {
        return toResponse(getRequiredReadableCard(userId, entityCardId));
    }

    @Transactional
    public EntityCardResponse create(Long userId, CreateEntityCardRequest request) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        Long projectId = request.getResearchProjectId() == null
                ? null
                : researchProjectService.getRequiredActiveProjectForWrite(userId, request.getResearchProjectId()).getId();
        EntityCard existing = findMergeTarget(personalSpace.getId(), projectId, request.getCanonicalName(), request.getAliases());
        if (existing != null) {
            mergeInto(existing, request);
            return toResponse(entityCardRepository.save(existing));
        }

        EntityCard card = new EntityCard();
        card.setSpaceId(personalSpace.getId());
        card.setResearchProjectId(projectId);
        card.setCanonicalName(normalizeRequired(request.getCanonicalName()));
        card.setNormalizedName(normalizeName(request.getCanonicalName()));
        card.setEntityType(request.getEntityType());
        card.setAliasesJson(writeList(mergeAliases(request.getCanonicalName(), request.getAliases())));
        card.setDescription(normalizeOptional(request.getDescription()));
        card.setExternalRefsJson(writeList(normalizeList(request.getExternalRefs())));
        card.setConfidence(normalizeConfidence(request.getConfidence()));
        card.setCardStatus(PersonalCardStatus.READY);
        return toResponse(entityCardRepository.save(card));
    }

    @Transactional
    public EntityCardResponse update(Long userId, Long entityCardId, UpdateEntityCardRequest request) {
        EntityCard card = getRequiredWritableCard(userId, entityCardId);
        card.setCanonicalName(normalizeRequired(request.getCanonicalName()));
        card.setNormalizedName(normalizeName(request.getCanonicalName()));
        card.setEntityType(request.getEntityType());
        card.setAliasesJson(writeList(mergeAliases(request.getCanonicalName(), request.getAliases())));
        card.setDescription(normalizeOptional(request.getDescription()));
        card.setExternalRefsJson(writeList(normalizeList(request.getExternalRefs())));
        card.setConfidence(normalizeConfidence(request.getConfidence()));
        card.setCardStatus(request.getCardStatus());
        return toResponse(entityCardRepository.save(card));
    }

    @Transactional
    public void linkConcept(Long userId, Long conceptCardId, LinkEntityRelationRequest request) {
        var conceptCard = conceptCardService.getRequiredCard(userId, conceptCardId);
        EntityCard entityCard = getRequiredWritableCard(userId, request.getEntityCardId());
        ensureScopeCompatible(entityCard, conceptCard.getResearchProjectId());
        String relationType = normalizeRelationType(request.getRelationType());
        conceptEntityRelationRepository.findByConceptCardIdAndEntityCardIdAndRelationType(conceptCardId, entityCard.getId(), relationType)
                .orElseGet(() -> {
                    ConceptEntityRelation relation = new ConceptEntityRelation();
                    relation.setConceptCardId(conceptCardId);
                    relation.setEntityCardId(entityCard.getId());
                    relation.setRelationType(relationType);
                    relation.setEvidence(normalizeOptional(request.getEvidence()));
                    return conceptEntityRelationRepository.save(relation);
                });
    }

    @Transactional
    public void linkClaim(Long userId, Long claimId, LinkEntityRelationRequest request) {
        var claim = claimService.getRequiredClaim(userId, claimId);
        EntityCard entityCard = getRequiredWritableCard(userId, request.getEntityCardId());
        ensureScopeCompatible(entityCard, claim.getResearchProjectId());
        String relationType = normalizeRelationType(request.getRelationType());
        claimEntityRelationRepository.findByClaimIdAndEntityCardIdAndRelationType(claimId, entityCard.getId(), relationType)
                .orElseGet(() -> {
                    ClaimEntityRelation relation = new ClaimEntityRelation();
                    relation.setClaimId(claimId);
                    relation.setEntityCardId(entityCard.getId());
                    relation.setRelationType(relationType);
                    relation.setEvidence(normalizeOptional(request.getEvidence()));
                    return claimEntityRelationRepository.save(relation);
                });
    }

    @Transactional(readOnly = true)
    public EntityCard getRequiredReadableCard(Long userId, Long entityCardId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        EntityCard entityCard = entityCardRepository.findByIdAndSpaceId(entityCardId, personalSpace.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_CARD_NOT_FOUND));
        if (entityCard.getResearchProjectId() != null) {
            researchProjectService.getRequiredActiveProject(userId, entityCard.getResearchProjectId());
        }
        return entityCard;
    }

    @Transactional
    public EntityCard getRequiredWritableCard(Long userId, Long entityCardId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        EntityCard entityCard = entityCardRepository.findByIdForUpdate(entityCardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENTITY_CARD_NOT_FOUND));
        if (!Objects.equals(entityCard.getSpaceId(), personalSpace.getId())) {
            throw new BusinessException(ErrorCode.ENTITY_CARD_ACCESS_DENIED, "No permission to modify this entity card");
        }
        if (entityCard.getResearchProjectId() != null) {
            researchProjectService.getRequiredActiveProjectForWrite(userId, entityCard.getResearchProjectId());
        }
        return entityCard;
    }

    private void ensureScopeCompatible(EntityCard entityCard, Long researchProjectId) {
        if (entityCard.getResearchProjectId() != null && !Objects.equals(entityCard.getResearchProjectId(), researchProjectId)) {
            throw new BusinessException(ErrorCode.ENTITY_CARD_ACCESS_DENIED, "Entity card belongs to a different research project");
        }
    }

    private EntityCard findMergeTarget(Long spaceId, Long researchProjectId, String canonicalName, List<String> aliases) {
        List<EntityCard> candidates = researchProjectId == null
                ? entityCardRepository.findBySpaceIdAndResearchProjectIdIsNullOrderByUpdatedAtDesc(spaceId)
                : entityCardRepository.findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(researchProjectId, spaceId);
        Set<String> incoming = new LinkedHashSet<>();
        incoming.add(normalizeName(canonicalName));
        normalizeList(aliases).stream().map(this::normalizeName).forEach(incoming::add);
        return candidates.stream()
                .filter(card -> {
                    if (incoming.contains(card.getNormalizedName())) {
                        return true;
                    }
                    Set<String> existingAliases = readList(card.getAliasesJson()).stream()
                            .map(this::normalizeName)
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                    existingAliases.add(card.getNormalizedName());
                    return existingAliases.stream().anyMatch(incoming::contains);
                })
                .findFirst()
                .orElse(null);
    }

    private void mergeInto(EntityCard card, CreateEntityCardRequest request) {
        card.setCanonicalName(normalizeRequired(card.getCanonicalName()));
        card.setAliasesJson(writeList(mergeAliases(card.getCanonicalName(), join(readList(card.getAliasesJson()), request.getAliases(), request.getCanonicalName()))));
        if ((card.getDescription() == null || card.getDescription().isBlank()) && request.getDescription() != null) {
            card.setDescription(normalizeOptional(request.getDescription()));
        }
        List<String> mergedExternalRefs = new ArrayList<>(readList(card.getExternalRefsJson()));
        for (String value : normalizeList(request.getExternalRefs())) {
            if (!mergedExternalRefs.contains(value)) {
                mergedExternalRefs.add(value);
            }
        }
        card.setExternalRefsJson(writeList(mergedExternalRefs));
        card.setConfidence(card.getConfidence().max(normalizeConfidence(request.getConfidence())));
        if (card.getEntityType() == null || card.getEntityType().name().equals("OTHER")) {
            card.setEntityType(request.getEntityType());
        }
    }

    private List<String> join(List<String> existing, List<String> incoming, String canonicalName) {
        List<String> values = new ArrayList<>();
        values.addAll(existing);
        if (incoming != null) {
            values.addAll(incoming);
        }
        values.add(canonicalName);
        return values;
    }

    private List<String> mergeAliases(String canonicalName, List<String> aliases) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : normalizeList(aliases)) {
            if (!normalizeName(value).equals(normalizeName(canonicalName))) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = normalizeOptional(value);
            if (item != null) {
                normalized.add(item);
            }
        }
        return List.copyOf(normalized);
    }

    private BigDecimal normalizeConfidence(BigDecimal confidence) {
        if (confidence == null) {
            return BigDecimal.valueOf(0.5d);
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (confidence.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return confidence;
    }

    private String normalizeRelationType(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return "RELATED";
        }
        return relationType.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRequired(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? "" : normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeName(String raw) {
        String normalized = normalizeOptional(raw);
        if (normalized == null) {
            return "";
        }
        return Normalizer.normalize(normalized, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<String> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse entity card json", ex);
        }
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize entity card json", ex);
        }
    }

    private EntityCardResponse toResponse(EntityCard card) {
        return EntityCardResponse.builder()
                .id(card.getId())
                .spaceId(card.getSpaceId())
                .researchProjectId(card.getResearchProjectId())
                .canonicalName(card.getCanonicalName())
                .normalizedName(card.getNormalizedName())
                .entityType(card.getEntityType())
                .aliases(readList(card.getAliasesJson()))
                .description(card.getDescription())
                .externalRefs(readList(card.getExternalRefsJson()))
                .confidence(card.getConfidence())
                .cardStatus(card.getCardStatus())
                .createdAt(card.getCreatedAt())
                .updatedAt(card.getUpdatedAt())
                .build();
    }
}
