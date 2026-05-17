package com.noteweave.personal.methodology.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.methodology.MethodologyCardJsonMapper;
import com.noteweave.personal.methodology.dto.CreateMethodologyCardRequest;
import com.noteweave.personal.methodology.dto.MethodologyCardResponse;
import com.noteweave.personal.methodology.dto.UpdateMethodologyCardRequest;
import com.noteweave.personal.methodology.model.MethodologyCard;
import com.noteweave.personal.methodology.model.MethodologyCardSource;
import com.noteweave.personal.methodology.model.MethodologyCardScope;
import com.noteweave.personal.methodology.model.MethodologyCardStatus;
import com.noteweave.personal.methodology.repository.MethodologyCardRepository;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.space.model.Space;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MethodologyCardService {

    private final MethodologyCardRepository methodologyCardRepository;
    private final ResearchProjectService researchProjectService;
    private final PersonalSpaceService personalSpaceService;
    private final MethodologyCardJsonMapper jsonMapper;

    @Transactional(readOnly = true)
    public List<MethodologyCardResponse> list(Long userId, Long researchProjectId) {
        ResearchProject project = researchProjectService.getRequiredActiveProject(userId, researchProjectId);
        Long personalSpaceId = project.getSpaceId();

        List<MethodologyCard> cards = new ArrayList<>();
        cards.addAll(methodologyCardRepository.findByResearchProjectIdAndSpaceIdAndStatusOrderByUpdatedAtDesc(
                researchProjectId,
                personalSpaceId,
                MethodologyCardStatus.ACTIVE
        ));
        cards.addAll(methodologyCardRepository.findBySpaceIdAndResearchProjectIdIsNullAndStatusOrderByUpdatedAtDesc(
                personalSpaceId,
                MethodologyCardStatus.ACTIVE
        ));
        cards.addAll(methodologyCardRepository.findByCardSourceAndStatusOrderByUpdatedAtDesc(
                MethodologyCardSource.PRESET,
                MethodologyCardStatus.ACTIVE
        ));
        return cards.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public MethodologyCardResponse get(Long userId, Long cardId) {
        return toResponse(getRequiredReadableCard(userId, cardId));
    }

    @Transactional
    public MethodologyCardResponse create(Long userId, Long researchProjectId, CreateMethodologyCardRequest request) {
        ResearchProject project = researchProjectService.getRequiredActiveProjectForWrite(userId, researchProjectId);
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        MethodologyCardScope cardScope = normalizeScope(request.getCardScope(), false);
        Long targetProjectId = resolveTargetProjectId(userId, cardScope, researchProjectId, null);
        ensureNoNameConflict(personalSpace.getId(), targetProjectId, request.getName(), null);

        MethodologyCard card = new MethodologyCard();
        card.setSpaceId(project.getSpaceId());
        card.setResearchProjectId(targetProjectId);
        card.setName(normalizeRequired(request.getName()));
        card.setScene(normalizeOptional(request.getScene()));
        card.setProblemType(normalizeOptional(request.getProblemType()));
        card.setWorkflowJson(jsonMapper.writeList(normalizeItems(request.getWorkflow())));
        card.setRequiredConceptsJson(jsonMapper.writeList(normalizeItems(request.getRequiredConcepts())));
        card.setOutputStructureJson(jsonMapper.writeList(normalizeItems(request.getOutputStructure())));
        card.setQualityChecklistJson(jsonMapper.writeList(normalizeItems(request.getQualityChecklist())));
        card.setCardSource(MethodologyCardSource.USER_CREATED);
        card.setCardScope(cardScope);
        card.setStatus(MethodologyCardStatus.ACTIVE);
        card.setVersion(1);
        card.setCreatedBy(userId);
        return toResponse(methodologyCardRepository.save(card));
    }

    @Transactional
    public MethodologyCardResponse update(Long userId, Long cardId, UpdateMethodologyCardRequest request) {
        MethodologyCard card = getRequiredWritableCard(userId, cardId);
        ensureEditable(card);

        MethodologyCardScope cardScope = normalizeScope(request.getCardScope(), true);
        Long targetProjectId = resolveTargetProjectId(userId, cardScope, request.getResearchProjectId(), card.getResearchProjectId());
        ensureNoNameConflict(card.getSpaceId(), targetProjectId, request.getName(), card.getId());

        List<String> workflow = normalizeItems(request.getWorkflow());
        List<String> requiredConcepts = normalizeItems(request.getRequiredConcepts());
        List<String> outputStructure = normalizeItems(request.getOutputStructure());
        List<String> qualityChecklist = normalizeItems(request.getQualityChecklist());

        boolean versionChanged = hasMethodologyStructureChanged(card, workflow, outputStructure, qualityChecklist);

        card.setResearchProjectId(targetProjectId);
        card.setCardScope(cardScope);
        card.setName(normalizeRequired(request.getName()));
        card.setScene(normalizeOptional(request.getScene()));
        card.setProblemType(normalizeOptional(request.getProblemType()));
        card.setWorkflowJson(jsonMapper.writeList(workflow));
        card.setRequiredConceptsJson(jsonMapper.writeList(requiredConcepts));
        card.setOutputStructureJson(jsonMapper.writeList(outputStructure));
        card.setQualityChecklistJson(jsonMapper.writeList(qualityChecklist));
        if (versionChanged) {
            card.setVersion(card.getVersion() == null ? 1 : card.getVersion() + 1);
        }
        return toResponse(methodologyCardRepository.save(card));
    }

    @Transactional
    public void archive(Long userId, Long cardId) {
        MethodologyCard card = getRequiredWritableCard(userId, cardId);
        ensureEditable(card);
        card.setStatus(MethodologyCardStatus.ARCHIVED);
        methodologyCardRepository.save(card);
    }

    private MethodologyCardResponse toResponse(MethodologyCard card) {
        return MethodologyCardResponse.builder()
                .id(card.getId())
                .spaceId(card.getSpaceId())
                .researchProjectId(card.getResearchProjectId())
                .name(card.getName())
                .scene(card.getScene())
                .problemType(card.getProblemType())
                .workflow(jsonMapper.readList(card.getWorkflowJson()))
                .requiredConcepts(jsonMapper.readList(card.getRequiredConceptsJson()))
                .outputStructure(jsonMapper.readList(card.getOutputStructureJson()))
                .qualityChecklist(jsonMapper.readList(card.getQualityChecklistJson()))
                .cardSource(card.getCardSource())
                .cardScope(card.getCardScope())
                .status(card.getStatus())
                .version(card.getVersion())
                .createdBy(card.getCreatedBy())
                .createdAt(card.getCreatedAt())
                .updatedAt(card.getUpdatedAt())
                .build();
    }

    private MethodologyCard getRequiredReadableCard(Long userId, Long cardId) {
        MethodologyCard card = methodologyCardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.METHODOLOGY_CARD_NOT_FOUND));
        if (card.getCardSource() == MethodologyCardSource.PRESET) {
            return card;
        }
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        if (!Objects.equals(card.getSpaceId(), personalSpace.getId()) || !isOwnedBy(userId, card)) {
            throw new BusinessException(ErrorCode.METHODOLOGY_CARD_ACCESS_DENIED, "No permission to access this methodology card");
        }
        return card;
    }

    private MethodologyCard getRequiredWritableCard(Long userId, Long cardId) {
        MethodologyCard card = methodologyCardRepository.findByIdForUpdate(cardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.METHODOLOGY_CARD_NOT_FOUND));
        if (card.getCardSource() == MethodologyCardSource.PRESET) {
            return card;
        }
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        if (!Objects.equals(card.getSpaceId(), personalSpace.getId()) || !isOwnedBy(userId, card)) {
            throw new BusinessException(ErrorCode.METHODOLOGY_CARD_ACCESS_DENIED, "No permission to modify this methodology card");
        }
        return card;
    }

    private void ensureEditable(MethodologyCard card) {
        if (card.getCardSource() == MethodologyCardSource.PRESET) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Preset methodology cards cannot be modified directly");
        }
    }

    private MethodologyCardScope normalizeScope(MethodologyCardScope requestedScope, boolean allowExistingScopedUpdate) {
        if (requestedScope == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "cardScope: must not be null");
        }
        if (requestedScope == MethodologyCardScope.SYSTEM) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    allowExistingScopedUpdate
                            ? "cardScope: SYSTEM scope is reserved for presets"
                            : "cardScope: SYSTEM scope cannot be created by users"
            );
        }
        return requestedScope;
    }

    private Long resolveTargetProjectId(Long userId, MethodologyCardScope cardScope, Long requestedProjectId, Long existingProjectId) {
        return switch (cardScope) {
            case SPACE -> null;
            case PROJECT -> {
                Long projectId = requestedProjectId != null ? requestedProjectId : existingProjectId;
                if (projectId == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "researchProjectId: is required for PROJECT scoped methodology cards");
                }
                yield researchProjectService.getRequiredActiveProjectForWrite(userId, projectId).getId();
            }
            case SYSTEM -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "cardScope: SYSTEM scope is reserved");
        };
    }

    private void ensureNoNameConflict(Long spaceId, Long researchProjectId, String rawName, Long excludeId) {
        String normalizedName = normalizeRequired(rawName);
        List<MethodologyCard> candidates = researchProjectId == null
                ? methodologyCardRepository.findBySpaceIdAndResearchProjectIdIsNullAndCardSource(spaceId, MethodologyCardSource.USER_CREATED)
                : methodologyCardRepository.findBySpaceIdAndResearchProjectIdAndCardSource(spaceId, researchProjectId, MethodologyCardSource.USER_CREATED);
        boolean conflict = candidates.stream()
                .filter(card -> card.getStatus() != MethodologyCardStatus.ARCHIVED)
                .filter(card -> excludeId == null || !card.getId().equals(excludeId))
                .anyMatch(card -> normalizeRequired(card.getName()).equalsIgnoreCase(normalizedName));
        if (conflict) {
            throw new BusinessException(ErrorCode.CONFLICT, "A methodology card with the same name already exists in this scope");
        }
    }

    private boolean hasMethodologyStructureChanged(
            MethodologyCard existing,
            List<String> workflow,
            List<String> outputStructure,
            List<String> qualityChecklist
    ) {
        return !jsonMapper.readList(existing.getWorkflowJson()).equals(workflow)
                || !jsonMapper.readList(existing.getOutputStructureJson()).equals(outputStructure)
                || !jsonMapper.readList(existing.getQualityChecklistJson()).equals(qualityChecklist);
    }

    private boolean isOwnedBy(Long userId, MethodologyCard card) {
        return card.getCreatedBy() == null || Objects.equals(card.getCreatedBy(), userId);
    }

    private List<String> normalizeItems(List<String> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private String normalizeRequired(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        String normalized = normalizeRequired(value);
        return normalized.isEmpty() ? null : normalized;
    }
}
