package com.noteweave.team.kb.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import com.noteweave.space.repository.SpaceRepository;
import com.noteweave.team.kb.dto.CreateKnowledgeBaseRequest;
import com.noteweave.team.kb.dto.KnowledgeBaseResponse;
import com.noteweave.team.kb.dto.UpdateKnowledgeBaseRequest;
import com.noteweave.team.kb.model.KnowledgeBase;
import com.noteweave.team.kb.model.KnowledgeBaseStatus;
import com.noteweave.team.kb.repository.KnowledgeBaseRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final SpaceRepository spaceRepository;
    private final ResourceAccessService resourceAccessService;

    @Transactional
    public KnowledgeBaseResponse create(Long userId, Long spaceId, CreateKnowledgeBaseRequest request) {
        resourceAccessService.requireUploadDocument(userId, spaceId);
        Space space = requireTeamSpace(spaceId);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setSpaceId(space.getId());
        kb.setName(normalize(request.getName()));
        kb.setDescription(normalizeNullable(request.getDescription()));
        kb.setCreatedBy(userId);
        kb.setStatus(KnowledgeBaseStatus.ACTIVE);
        return toResponse(knowledgeBaseRepository.save(kb));
    }

    public List<KnowledgeBaseResponse> list(Long userId, Long spaceId) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        return knowledgeBaseRepository.findBySpaceIdAndStatusOrderByCreatedAtDesc(spaceId, KnowledgeBaseStatus.ACTIVE)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public KnowledgeBaseResponse get(Long userId, Long knowledgeBaseId) {
        KnowledgeBase kb = getRequiredActiveKb(knowledgeBaseId);
        resourceAccessService.requireViewSpace(userId, kb.getSpaceId());
        return toResponse(kb);
    }

    @Transactional
    public KnowledgeBaseResponse update(Long userId, Long knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase kb = knowledgeBaseRepository.findByIdForUpdate(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        if (kb.getStatus() != KnowledgeBaseStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        resourceAccessService.requireUploadDocument(userId, kb.getSpaceId());
        kb.setName(normalize(request.getName()));
        kb.setDescription(normalizeNullable(request.getDescription()));
        return toResponse(knowledgeBaseRepository.save(kb));
    }

    @Transactional
    public void archive(Long userId, Long knowledgeBaseId) {
        KnowledgeBase kb = knowledgeBaseRepository.findByIdForUpdate(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        resourceAccessService.requireUploadDocument(userId, kb.getSpaceId());
        if (kb.getStatus() == KnowledgeBaseStatus.ARCHIVED) {
            return;
        }
        kb.setStatus(KnowledgeBaseStatus.ARCHIVED);
        kb.setDeletedAt(LocalDateTime.now());
        kb.setDeletedBy(userId);
        knowledgeBaseRepository.save(kb);
    }

    public KnowledgeBase getRequiredActiveKb(Long knowledgeBaseId) {
        return knowledgeBaseRepository.findByIdAndStatus(knowledgeBaseId, KnowledgeBaseStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    }

    private Space requireTeamSpace(Long spaceId) {
        Space space = spaceRepository.findByIdAndStatus(spaceId, SpaceStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.SPACE_NOT_FOUND));
        if (space.getType() != SpaceType.TEAM) {
            throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "knowledge base requires TEAM space");
        }
        return space;
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase kb) {
        return KnowledgeBaseResponse.builder()
                .id(kb.getId())
                .spaceId(kb.getSpaceId())
                .name(kb.getName())
                .description(kb.getDescription())
                .status(kb.getStatus())
                .createdBy(kb.getCreatedBy())
                .createdAt(kb.getCreatedAt())
                .updatedAt(kb.getUpdatedAt())
                .build();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
