package com.noteweave.personal.project.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.project.dto.CreateResearchProjectRequest;
import com.noteweave.personal.project.dto.ResearchProjectResponse;
import com.noteweave.personal.project.dto.UpdateResearchProjectRequest;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.model.ResearchProjectStatus;
import com.noteweave.personal.project.repository.ResearchProjectRepository;
import com.noteweave.personal.source.model.Source;
import com.noteweave.personal.source.repository.SourceRepository;
import com.noteweave.space.model.Space;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResearchProjectService {

    private final ResearchProjectRepository researchProjectRepository;
    private final SourceRepository sourceRepository;
    private final PersonalSpaceService personalSpaceService;

    @Transactional
    public ResearchProjectResponse create(Long userId, CreateResearchProjectRequest request) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        ResearchProject project = new ResearchProject();
        project.setSpaceId(personalSpace.getId());
        project.setUserId(userId);
        project.setTitle(normalizeRequired(request.getTitle()));
        project.setDescription(normalizeOptional(request.getDescription()));
        project.setResearchGoal(normalizeOptional(request.getResearchGoal()));
        return toResponse(researchProjectRepository.save(project));
    }

    @Transactional(readOnly = true)
    public List<ResearchProjectResponse> listMine(Long userId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        return researchProjectRepository.findBySpaceIdAndDeletedAtIsNullAndStatusOrderByCreatedAtDesc(
                        personalSpace.getId(),
                        ResearchProjectStatus.ACTIVE
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ResearchProjectResponse get(Long userId, Long projectId) {
        return toResponse(getRequiredActiveProject(userId, projectId));
    }

    @Transactional
    public ResearchProjectResponse update(Long userId, Long projectId, UpdateResearchProjectRequest request) {
        ResearchProject project = getRequiredActiveProjectForWrite(userId, projectId);
        project.setTitle(normalizeRequired(request.getTitle()));
        project.setDescription(normalizeOptional(request.getDescription()));
        project.setResearchGoal(normalizeOptional(request.getResearchGoal()));
        return toResponse(researchProjectRepository.save(project));
    }

    @Transactional
    public void archive(Long userId, Long projectId) {
        ResearchProject project = getRequiredActiveProjectForWrite(userId, projectId);
        LocalDateTime now = LocalDateTime.now();
        project.setStatus(ResearchProjectStatus.ARCHIVED);
        project.setDeletedAt(now);
        project.setDeletedBy(userId);
        researchProjectRepository.save(project);

        List<Source> sources = sourceRepository.findByResearchProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(projectId);
        for (Source source : sources) {
            source.setDeletedAt(now);
            source.setDeletedBy(userId);
        }
        sourceRepository.saveAll(sources);
    }

    @Transactional(readOnly = true)
    public ResearchProject getRequiredActiveProject(Long userId, Long projectId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        return researchProjectRepository.findByIdAndSpaceIdAndDeletedAtIsNullAndStatus(
                        projectId,
                        personalSpace.getId(),
                        ResearchProjectStatus.ACTIVE
                )
                .orElseThrow(() -> accessError(projectId, personalSpace.getId()));
    }

    @Transactional
    public ResearchProject getRequiredActiveProjectForWrite(Long userId, Long projectId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        ResearchProject project = researchProjectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESEARCH_PROJECT_NOT_FOUND));
        if (project.getDeletedAt() != null || project.getStatus() != ResearchProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.RESEARCH_PROJECT_NOT_FOUND);
        }
        if (!project.getSpaceId().equals(personalSpace.getId())) {
            throw new BusinessException(ErrorCode.RESEARCH_PROJECT_ACCESS_DENIED, "No permission to access this research project");
        }
        return project;
    }

    private BusinessException accessError(Long projectId, Long personalSpaceId) {
        return researchProjectRepository.findById(projectId)
                .filter(project -> project.getDeletedAt() == null && project.getStatus() == ResearchProjectStatus.ACTIVE)
                .map(project -> !project.getSpaceId().equals(personalSpaceId)
                        ? new BusinessException(ErrorCode.RESEARCH_PROJECT_ACCESS_DENIED, "No permission to access this research project")
                        : new BusinessException(ErrorCode.RESEARCH_PROJECT_NOT_FOUND))
                .orElseGet(() -> new BusinessException(ErrorCode.RESEARCH_PROJECT_NOT_FOUND));
    }

    private ResearchProjectResponse toResponse(ResearchProject project) {
        return ResearchProjectResponse.builder()
                .id(project.getId())
                .spaceId(project.getSpaceId())
                .userId(project.getUserId())
                .title(project.getTitle())
                .description(project.getDescription())
                .researchGoal(project.getResearchGoal())
                .compileStatus(project.getCompileStatus())
                .status(project.getStatus())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    private String normalizeRequired(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
