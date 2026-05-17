package com.noteweave.prompt.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.prompt.dto.CreatePromptVersionRequest;
import com.noteweave.prompt.dto.PromptVersionResponse;
import com.noteweave.prompt.model.PromptVersion;
import com.noteweave.prompt.repository.PromptVersionRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PromptVersionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private final PromptVersionRepository promptVersionRepository;

    @Transactional
    public PromptVersionResponse create(Long userId, CreatePromptVersionRequest request) {
        PromptVersion promptVersion = new PromptVersion();
        promptVersion.setName(request.getName().trim());
        promptVersion.setScene(request.getScene().trim().toUpperCase());
        promptVersion.setVersion(request.getVersion());
        promptVersion.setContent(request.getContent().trim());
        promptVersion.setVariablesJson(normalize(request.getVariablesJson()));
        promptVersion.setStatus(normalizeStatus(request.getStatus(), STATUS_DRAFT));
        promptVersion.setCreatedBy(userId);
        PromptVersion saved = promptVersionRepository.save(promptVersion);
        if (STATUS_ACTIVE.equals(saved.getStatus())) {
            activate(userId, saved.getId());
            saved = promptVersionRepository.findById(saved.getId()).orElse(saved);
        }
        return toResponse(saved);
    }

    @Transactional
    public PromptVersionResponse activate(Long userId, Long promptVersionId) {
        PromptVersion target = promptVersionRepository.findById(promptVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Prompt version not found"));
        for (PromptVersion active : promptVersionRepository.findAllBySceneAndStatus(target.getScene(), STATUS_ACTIVE)) {
            if (!active.getId().equals(target.getId())) {
                active.setStatus(STATUS_ARCHIVED);
                promptVersionRepository.save(active);
            }
        }
        target.setStatus(STATUS_ACTIVE);
        return toResponse(promptVersionRepository.save(target));
    }

    @Transactional(readOnly = true)
    public PromptVersionResponse getActive(String scene) {
        return toResponse(findActiveEntity(scene)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Active prompt version not found")));
    }

    @Transactional(readOnly = true)
    public List<PromptVersionResponse> list(String scene) {
        String normalizedScene = scene == null ? null : scene.trim().toUpperCase();
        if (normalizedScene == null || normalizedScene.isBlank()) {
            return promptVersionRepository.findAll().stream()
                    .sorted((left, right) -> right.getCreatedAt().compareTo(left.getCreatedAt()))
                    .map(this::toResponse)
                    .toList();
        }
        return promptVersionRepository.findBySceneOrderByVersionDesc(normalizedScene).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PromptVersion> findActiveEntity(String scene) {
        if (scene == null || scene.isBlank()) {
            return Optional.empty();
        }
        return promptVersionRepository.findBySceneAndStatus(scene.trim().toUpperCase(), STATUS_ACTIVE);
    }

    private String normalizeStatus(String rawStatus, String defaultStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return defaultStatus;
        }
        String normalized = rawStatus.trim().toUpperCase();
        if (STATUS_ACTIVE.equals(normalized) || STATUS_DRAFT.equals(normalized) || STATUS_ARCHIVED.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported prompt status: " + rawStatus);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private PromptVersionResponse toResponse(PromptVersion promptVersion) {
        return PromptVersionResponse.builder()
                .id(promptVersion.getId())
                .name(promptVersion.getName())
                .scene(promptVersion.getScene())
                .version(promptVersion.getVersion())
                .content(promptVersion.getContent())
                .variablesJson(promptVersion.getVariablesJson())
                .status(promptVersion.getStatus())
                .createdBy(promptVersion.getCreatedBy())
                .createdAt(promptVersion.getCreatedAt())
                .updatedAt(promptVersion.getUpdatedAt())
                .build();
    }
}
