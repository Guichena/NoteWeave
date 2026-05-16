package com.noteweave.studio.service;

import com.noteweave.artifact.dto.RegenerateArtifactRequest;
import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.model.ArtifactScopeType;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactType;
import com.noteweave.artifact.model.SessionArtifact;
import com.noteweave.artifact.model.SessionArtifactRelationType;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.artifact.repository.SessionArtifactRepository;
import com.noteweave.artifact.service.ArtifactService;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.chat.service.ChatSessionService;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.common.security.CurrentUser;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.studio.dto.CreateStudioTaskRequest;
import com.noteweave.studio.dto.CreateStudioTaskResponse;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.repository.TaskRepository;
import com.noteweave.task.service.TaskCreateCommand;
import com.noteweave.task.service.TaskService;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StudioTaskService {

    private static final String ARTIFACT_TARGET_TYPE = "ARTIFACT";

    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final ArtifactRepository artifactRepository;
    private final SessionArtifactRepository sessionArtifactRepository;
    private final ArtifactService artifactService;
    private final ResourceAccessService resourceAccessService;
    private final ResearchProjectService researchProjectService;
    private final ChatSessionService chatSessionService;
    private final ChatMessageRepository chatMessageRepository;

    @Transactional
    public CreateStudioTaskResponse createTask(Long userId, CreateStudioTaskRequest request) {
        if (request.getTaskType() != TaskType.ARTIFACT_GENERATE) {
            throw new BusinessException(ErrorCode.STUDIO_TASK_TYPE_UNSUPPORTED);
        }
        resourceAccessService.requireAskQuestion(userId, request.getSpaceId());
        ArtifactType artifactType = extractArtifactType(request.getParams());
        ArtifactScopeType scopeType = parseScopeType(request.getSourceScopeType());
        validateScope(userId, request.getSpaceId(), request.getResearchProjectId(), scopeType, request.getSourceIds(), request.getCreatedFromSessionId(), request.getCreatedFromMessageId());

        String idempotencyKey = buildCreateIdempotencyKey(userId, request.getSpaceId(), request.getResearchProjectId(), artifactType, scopeType, request.getSourceIds(), request.getCreatedFromSessionId(), request.getCreatedFromMessageId(), request.getParams());
        Task existing = taskRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            Artifact existingArtifact = existing.getTargetId() == null ? null : artifactRepository.findById(existing.getTargetId()).orElse(null);
            return CreateStudioTaskResponse.builder()
                    .taskId(existing.getId())
                    .artifactId(existingArtifact == null ? existing.getTargetId() : existingArtifact.getId())
                    .taskStatus(existing.getTaskStatus())
                    .artifactStatus(existingArtifact == null ? ArtifactStatus.GENERATING : existingArtifact.getStatus())
                    .build();
        }

        Artifact artifact = new Artifact();
        artifact.setUserId(userId);
        artifact.setSpaceId(request.getSpaceId());
        artifact.setResearchProjectId(request.getResearchProjectId());
        artifact.setCreatedFromSessionId(request.getCreatedFromSessionId());
        artifact.setCreatedFromMessageId(request.getCreatedFromMessageId());
        artifact.setArtifactType(artifactType);
        artifact.setTitle(resolveTopic(request.getParams(), artifactType));
        artifact.setContent(null);
        artifact.setSourceScopeType(scopeType);
        artifact.setStatus(ArtifactStatus.GENERATING);
        artifact = artifactRepository.save(artifact);

        TaskResponse task = taskService.createTask(TaskCreateCommand.builder()
                .userId(userId)
                .spaceId(request.getSpaceId())
                .researchProjectId(request.getResearchProjectId())
                .taskType(TaskType.ARTIFACT_GENERATE)
                .targetType(ARTIFACT_TARGET_TYPE)
                .targetId(artifact.getId())
                .idempotencyKey(idempotencyKey)
                .input(buildTaskInput(artifact.getId(), request.getSpaceId(), request.getResearchProjectId(), artifactType, scopeType, request.getSourceIds(), request.getCreatedFromSessionId(), request.getCreatedFromMessageId(), request.getParams()))
                .build());

        if (!artifact.getId().equals(task.getTargetId())) {
            Artifact existingArtifact = artifactRepository.findById(task.getTargetId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND));
            artifact.setStatus(ArtifactStatus.ARCHIVED);
            artifact.setDeletedAt(java.time.LocalDateTime.now());
            artifact.setDeletedBy(userId);
            artifactRepository.save(artifact);
            return CreateStudioTaskResponse.builder()
                    .taskId(task.getId())
                    .artifactId(existingArtifact.getId())
                    .taskStatus(task.getTaskStatus())
                    .artifactStatus(existingArtifact.getStatus())
                    .build();
        }

        artifact.setTaskId(task.getId());
        artifactRepository.save(artifact);
        createSessionRelationIfNeeded(artifact);
        return CreateStudioTaskResponse.builder()
                .taskId(task.getId())
                .artifactId(artifact.getId())
                .taskStatus(task.getTaskStatus())
                .artifactStatus(artifact.getStatus())
                .build();
    }

    @Transactional
    public CreateStudioTaskResponse regenerate(Long userId, Long artifactId, RegenerateArtifactRequest request) {
        Artifact artifact = artifactService.getRequiredWritableArtifact(userId, artifactId);
        resourceAccessService.requireAskQuestion(userId, artifact.getSpaceId());

        Task activeTask = taskRepository.findTopByTaskTypeAndTargetTypeAndTargetIdAndTaskStatusInOrderByCreatedAtDesc(
                        TaskType.ARTIFACT_GENERATE,
                        ARTIFACT_TARGET_TYPE,
                        artifact.getId(),
                        EnumSet.of(TaskStatus.PENDING, TaskStatus.RUNNING)
                )
                .orElse(null);
        if (activeTask != null) {
            return CreateStudioTaskResponse.builder()
                    .taskId(activeTask.getId())
                    .artifactId(artifact.getId())
                    .taskStatus(activeTask.getTaskStatus())
                    .artifactStatus(artifact.getStatus())
                    .build();
        }

        long attempt = taskRepository.countByTaskTypeAndTargetTypeAndTargetId(TaskType.ARTIFACT_GENERATE, ARTIFACT_TARGET_TYPE, artifact.getId()) + 1;
        Map<String, Object> params = request == null || request.getParams() == null ? Map.of() : request.getParams();
        TaskResponse task = taskService.createTask(TaskCreateCommand.builder()
                .userId(userId)
                .spaceId(artifact.getSpaceId())
                .researchProjectId(artifact.getResearchProjectId())
                .taskType(TaskType.ARTIFACT_GENERATE)
                .targetType(ARTIFACT_TARGET_TYPE)
                .targetId(artifact.getId())
                .idempotencyKey("ARTIFACT_GENERATE:" + artifact.getId() + ":attempt:" + attempt)
                .input(buildTaskInput(
                        artifact.getId(),
                        artifact.getSpaceId(),
                        artifact.getResearchProjectId(),
                        artifact.getArtifactType(),
                        artifact.getSourceScopeType(),
                        regenerateSourceIds(artifact),
                        artifact.getCreatedFromSessionId(),
                        artifact.getCreatedFromMessageId(),
                        params.isEmpty() ? Map.of("topic", artifact.getTitle()) : params
                ))
                .build());

        artifact.setTaskId(task.getId());
        artifact.setStatus(ArtifactStatus.GENERATING);
        artifactRepository.save(artifact);
        createSessionRelationIfNeeded(artifact);
        return CreateStudioTaskResponse.builder()
                .taskId(task.getId())
                .artifactId(artifact.getId())
                .taskStatus(task.getTaskStatus())
                .artifactStatus(artifact.getStatus())
                .build();
    }

    public TaskResponse getTask(CurrentUser currentUser, Long taskId) {
        return taskService.getTask(currentUser, taskId);
    }

    @Transactional
    public void cancel(CurrentUser currentUser, Long taskId) {
        taskService.cancelTask(currentUser, taskId);
    }

    @Transactional
    public TaskResponse retry(CurrentUser currentUser, Long taskId) {
        return taskService.retryTask(currentUser, taskId);
    }

    private void validateScope(
            Long userId,
            Long spaceId,
            Long researchProjectId,
            ArtifactScopeType scopeType,
            List<Long> sourceIds,
            Long createdFromSessionId,
            Long createdFromMessageId
    ) {
        if (scopeType == ArtifactScopeType.RESEARCH_PROJECT) {
            Long projectId = researchProjectId != null ? researchProjectId : firstId(sourceIds);
            ResearchProject project = researchProjectService.getRequiredActiveProject(userId, projectId);
            if (!project.getSpaceId().equals(spaceId)) {
                throw new BusinessException(ErrorCode.RESEARCH_PROJECT_ACCESS_DENIED, "Research project is outside the requested space");
            }
            return;
        }

        Long messageId = createdFromMessageId != null ? createdFromMessageId : firstId(sourceIds);
        ChatMessage message = chatSessionService.getRequiredMessage(userId, messageId);
        ChatSession session = chatSessionService.getSessionByMessageId(userId, messageId);
        if (!session.getSpaceId().equals(spaceId)) {
            throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "Chat message is outside the requested space");
        }
        if (createdFromSessionId != null && !createdFromSessionId.equals(session.getId())) {
            throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "Chat message does not belong to the requested session");
        }
        if (!message.getSessionId().equals(session.getId())) {
            throw new BusinessException(ErrorCode.SPACE_ACCESS_DENIED, "Chat message does not belong to the requested session");
        }
    }

    private Map<String, Object> buildTaskInput(
            Long artifactId,
            Long spaceId,
            Long researchProjectId,
            ArtifactType artifactType,
            ArtifactScopeType scopeType,
            List<Long> sourceIds,
            Long createdFromSessionId,
            Long createdFromMessageId,
            Map<String, Object> params
    ) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("artifactId", artifactId);
        input.put("spaceId", spaceId);
        input.put("researchProjectId", researchProjectId);
        input.put("artifactType", artifactType.name());
        input.put("sourceScopeType", scopeType.name());
        input.put("sourceIds", sourceIds);
        input.put("createdFromSessionId", createdFromSessionId);
        input.put("createdFromMessageId", createdFromMessageId);
        input.put("params", params == null ? Map.of() : params);
        return input;
    }

    private String buildCreateIdempotencyKey(
            Long userId,
            Long spaceId,
            Long researchProjectId,
            ArtifactType artifactType,
            ArtifactScopeType scopeType,
            List<Long> sourceIds,
            Long createdFromSessionId,
            Long createdFromMessageId,
            Map<String, Object> params
    ) {
        String payloadHash = Integer.toHexString(Objects.hash(
                userId,
                spaceId,
                researchProjectId,
                artifactType,
                scopeType,
                sourceIds,
                createdFromSessionId,
                createdFromMessageId,
                params == null ? Map.of() : params
        ));
        return "ARTIFACT_GENERATE:" + spaceId + ":" + artifactType.name() + ":" + scopeType.name() + ":" + payloadHash;
    }

    private void createSessionRelationIfNeeded(Artifact artifact) {
        if (artifact.getCreatedFromSessionId() == null) {
            return;
        }
        sessionArtifactRepository.findBySessionIdAndArtifactIdAndRelationType(
                        artifact.getCreatedFromSessionId(),
                        artifact.getId(),
                        SessionArtifactRelationType.CREATED_FROM
                )
                .orElseGet(() -> {
                    SessionArtifact relation = new SessionArtifact();
                    relation.setSessionId(artifact.getCreatedFromSessionId());
                    relation.setArtifactId(artifact.getId());
                    relation.setRelationType(SessionArtifactRelationType.CREATED_FROM);
                    return sessionArtifactRepository.save(relation);
                });
    }

    private ArtifactType extractArtifactType(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("artifactType");
        if (raw == null) {
            throw new BusinessException(ErrorCode.ARTIFACT_TYPE_UNSUPPORTED, "artifactType is required");
        }
        try {
            ArtifactType artifactType = ArtifactType.valueOf(raw.toString().trim().toUpperCase());
            if (!EnumSet.of(
                    ArtifactType.REPORT,
                    ArtifactType.STUDY_GUIDE,
                    ArtifactType.BRIEFING,
                    ArtifactType.FAQ,
                    ArtifactType.COMPARISON,
                    ArtifactType.WIKI_DRAFT
            ).contains(artifactType)) {
                throw new BusinessException(ErrorCode.ARTIFACT_TYPE_UNSUPPORTED);
            }
            return artifactType;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.ARTIFACT_TYPE_UNSUPPORTED, "Unsupported artifact type");
        }
    }

    private ArtifactScopeType parseScopeType(String raw) {
        try {
            return ArtifactScopeType.valueOf(raw.trim().toUpperCase());
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "sourceScopeType: unsupported scope");
        }
    }

    private String resolveTopic(Map<String, Object> params, ArtifactType artifactType) {
        Object topic = params == null ? null : params.get("topic");
        if (topic != null && !topic.toString().isBlank()) {
            return topic.toString().trim();
        }
        return artifactType.name().replace('_', ' ');
    }

    private List<Long> regenerateSourceIds(Artifact artifact) {
        if (artifact.getSourceScopeType() == ArtifactScopeType.RESEARCH_PROJECT) {
            return List.of(artifact.getResearchProjectId());
        }
        if (artifact.getCreatedFromMessageId() != null) {
            return List.of(artifact.getCreatedFromMessageId());
        }
        return List.of();
    }

    private Long firstId(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "sourceIds must not be empty");
        }
        return ids.get(0);
    }
}
