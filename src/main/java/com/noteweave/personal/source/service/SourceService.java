package com.noteweave.personal.source.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.common.PersonalSpaceService;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.service.ResearchProjectCompileStatusService;
import com.noteweave.personal.project.service.ResearchProjectService;
import com.noteweave.personal.source.fetch.UrlContentFetcher;
import com.noteweave.personal.source.dto.AddTextSourceRequest;
import com.noteweave.personal.source.dto.AddUrlSourceRequest;
import com.noteweave.personal.source.dto.SourceResponse;
import com.noteweave.personal.source.dto.UploadSourceRequest;
import com.noteweave.personal.source.model.SourceCompileStatus;
import com.noteweave.personal.source.model.Source;
import com.noteweave.personal.source.model.SourceImportStatus;
import com.noteweave.personal.source.model.SourceType;
import com.noteweave.personal.source.repository.SourceRepository;
import com.noteweave.space.model.Space;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.repository.TaskRepository;
import com.noteweave.task.service.TaskCreateCommand;
import com.noteweave.task.service.TaskService;
import com.noteweave.team.document.parser.DocumentParserService;
import com.noteweave.team.wiki.service.AutoWikiMaintenanceService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class SourceService {

    private static final String SOURCE_TARGET_TYPE = "SOURCE";

    private final SourceRepository sourceRepository;
    private final ResearchProjectService researchProjectService;
    private final PersonalSpaceService personalSpaceService;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final SourceStorageSupport sourceStorageSupport;
    private final DocumentParserService documentParserService;
    private final UrlContentFetcher urlContentFetcher;
    private final ResearchProjectCompileStatusService researchProjectCompileStatusService;
    private final AutoWikiMaintenanceService autoWikiMaintenanceService;

    @Transactional
    public SourceResponse uploadFile(Long userId, Long projectId, MultipartFile file, UploadSourceRequest request) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "File is required");
        }
        ResearchProject project = researchProjectService.getRequiredActiveProjectForWrite(userId, projectId);
        String fileName = normalizeFileName(file.getOriginalFilename());
        String contentType = normalizeOptional(file.getContentType());
        if (!documentParserService.supports(fileName, contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE, "Unsupported document type");
        }
        byte[] bytes = readBytes(file);
        String contentHash = sha256(bytes);
        Source duplicate = findDuplicateByContentHash(project.getId(), contentHash);
        if (duplicate != null) {
            return toResponse(duplicate);
        }

        String objectKey = sourceStorageSupport.buildFileObjectKey(contentHash, fileName);
        if (!sourceStorageSupport.objectExists(objectKey)) {
            sourceStorageSupport.putOriginalObject(objectKey, bytes, contentType);
        }

        Source source = new Source();
        source.setSpaceId(project.getSpaceId());
        source.setResearchProjectId(project.getId());
        source.setTitle(normalizeTitle(request == null ? null : request.getTitle(), fileName));
        source.setSourceType(SourceType.FILE);
        source.setObjectKey(objectKey);
        source.setContentHash(contentHash);
        source.setCreatedBy(userId);
        source = sourceRepository.save(source);

        Long taskId = ensureSourceImportTask(source, userId, false);
        researchProjectCompileStatusService.refresh(project.getId());
        return toResponse(source, taskId);
    }

    @Transactional
    public SourceResponse addUrl(Long userId, Long projectId, AddUrlSourceRequest request) {
        ResearchProject project = researchProjectService.getRequiredActiveProjectForWrite(userId, projectId);
        String normalizedUrl = normalizeUrl(request.getUrl());
        urlContentFetcher.validate(normalizedUrl);
        Source existing = sourceRepository.findFirstByResearchProjectIdAndDeletedAtIsNullAndSourceTypeAndUrlOrderByCreatedAtAsc(
                        project.getId(),
                        SourceType.URL,
                        normalizedUrl
                )
                .orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }

        Source source = new Source();
        source.setSpaceId(project.getSpaceId());
        source.setResearchProjectId(project.getId());
        source.setTitle(normalizeTitle(request.getTitle(), normalizedUrl));
        source.setSourceType(SourceType.URL);
        source.setUrl(normalizedUrl);
        source.setCreatedBy(userId);
        source = sourceRepository.save(source);

        Long taskId = ensureSourceImportTask(source, userId, false);
        researchProjectCompileStatusService.refresh(project.getId());
        return toResponse(source, taskId);
    }

    @Transactional
    public SourceResponse addText(Long userId, Long projectId, AddTextSourceRequest request) {
        ResearchProject project = researchProjectService.getRequiredActiveProjectForWrite(userId, projectId);
        String content = normalizeRequired(request.getContent());
        String contentHash = sha256(content.getBytes(StandardCharsets.UTF_8));
        Source duplicate = findDuplicateByContentHash(project.getId(), contentHash);
        if (duplicate != null) {
            return toResponse(duplicate);
        }

        Source source = new Source();
        source.setSpaceId(project.getSpaceId());
        source.setResearchProjectId(project.getId());
        source.setTitle(normalizeRequired(request.getTitle()));
        source.setSourceType(SourceType.TEXT);
        source.setContentHash(contentHash);
        source.setCreatedBy(userId);
        source = sourceRepository.save(source);

        String rawTextObjectKey = sourceStorageSupport.rawTextObjectKey(source.getId(), 1);
        sourceStorageSupport.writeTextObject(rawTextObjectKey, content);
        if (!sourceStorageSupport.objectExists(rawTextObjectKey)) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Raw text object was not created");
        }

        source.setRawTextObjectKey(rawTextObjectKey);
        source.setImportStatus(SourceImportStatus.READY);
        source.setTokenCount(countTokens(content));
        source.setErrorMessage(null);
        source = sourceRepository.save(source);
        researchProjectCompileStatusService.refresh(project.getId());
        return toResponse(source, null);
    }

    @Transactional(readOnly = true)
    public List<SourceResponse> list(Long userId, Long projectId) {
        ResearchProject project = researchProjectService.getRequiredActiveProject(userId, projectId);
        return sourceRepository.findByResearchProjectIdAndSpaceIdAndDeletedAtIsNullOrderByCreatedAtDesc(project.getId(), project.getSpaceId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public SourceResponse get(Long userId, Long sourceId) {
        return toResponse(getRequiredSource(userId, sourceId));
    }

    @Transactional
    public void delete(Long userId, Long sourceId) {
        Source source = getRequiredSourceForWrite(userId, sourceId);
        source.setDeletedAt(LocalDateTime.now());
        source.setDeletedBy(userId);
        sourceRepository.save(source);
        autoWikiMaintenanceService.archivePersonalSourceWiki(userId, source);
        researchProjectCompileStatusService.refresh(source.getResearchProjectId());
    }

    @Transactional
    public SourceResponse triggerImport(Long userId, Long sourceId) {
        Source source = getRequiredSourceForWrite(userId, sourceId);
        Long taskId = ensureSourceImportTask(source, userId, true);
        source = sourceRepository.save(source);
        researchProjectCompileStatusService.refresh(source.getResearchProjectId());
        return toResponse(source, taskId);
    }

    @Transactional(readOnly = true)
    public Source getRequiredSource(Long userId, Long sourceId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        Source source = sourceRepository.findByIdAndSpaceIdAndDeletedAtIsNull(sourceId, personalSpace.getId())
                .orElseThrow(() -> sourceAccessError(sourceId, personalSpace.getId()));
        ensureParentProjectActiveForRead(userId, source);
        return source;
    }

    @Transactional
    public Source getRequiredSourceForWrite(Long userId, Long sourceId) {
        Space personalSpace = personalSpaceService.getRequiredPersonalSpace(userId);
        Source source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SOURCE_NOT_FOUND));
        if (source.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.SOURCE_NOT_FOUND);
        }
        if (!source.getSpaceId().equals(personalSpace.getId())) {
            throw new BusinessException(ErrorCode.SOURCE_ACCESS_DENIED, "No permission to access this source");
        }
        ensureParentProjectActiveForWrite(userId, source);
        Source lockedSource = sourceRepository.findByIdForUpdate(sourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SOURCE_NOT_FOUND));
        if (lockedSource.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.SOURCE_NOT_FOUND);
        }
        if (!lockedSource.getSpaceId().equals(personalSpace.getId())) {
            throw new BusinessException(ErrorCode.SOURCE_ACCESS_DENIED, "No permission to access this source");
        }
        return lockedSource;
    }

    private Source findDuplicateByContentHash(Long projectId, String contentHash) {
        return sourceRepository.findFirstByResearchProjectIdAndDeletedAtIsNullAndContentHashOrderByCreatedAtAsc(projectId, contentHash)
                .orElse(null);
    }

    private Long ensureSourceImportTask(Source source, Long userId, boolean reuseActiveTask) {
        if (reuseActiveTask) {
            Task activeTask = taskRepository.findTopByTaskTypeAndTargetTypeAndTargetIdAndTaskStatusInOrderByCreatedAtDesc(
                            TaskType.SOURCE_IMPORT,
                            SOURCE_TARGET_TYPE,
                            source.getId(),
                            EnumSet.of(TaskStatus.PENDING, TaskStatus.RUNNING)
                    )
                    .orElse(null);
            if (activeTask != null) {
                return activeTask.getId();
            }
            source.setImportStatus(SourceImportStatus.PENDING);
            source.setCompileStatus(SourceCompileStatus.PENDING);
            source.setErrorMessage(null);
        }

        long attempt = taskRepository.countByTaskTypeAndTargetTypeAndTargetId(TaskType.SOURCE_IMPORT, SOURCE_TARGET_TYPE, source.getId()) + 1;
        Map<String, Object> input = new HashMap<>();
        input.put("sourceId", source.getId());
        input.put("researchProjectId", source.getResearchProjectId());
        input.put("spaceId", source.getSpaceId());
        input.put("sourceType", source.getSourceType().name());
        input.put("objectKey", source.getObjectKey());
        input.put("url", source.getUrl());
        input.put("title", source.getTitle());

        return taskService.createTask(TaskCreateCommand.builder()
                        .userId(userId)
                        .spaceId(source.getSpaceId())
                        .researchProjectId(source.getResearchProjectId())
                        .taskType(TaskType.SOURCE_IMPORT)
                        .targetType(SOURCE_TARGET_TYPE)
                        .targetId(source.getId())
                        .idempotencyKey("SOURCE_IMPORT:" + source.getId() + ":attempt:" + attempt)
                        .input(input)
                        .build())
                .getId();
    }

    private void ensureParentProjectActiveForRead(Long userId, Source source) {
        try {
            researchProjectService.getRequiredActiveProject(userId, source.getResearchProjectId());
        } catch (BusinessException ex) {
            throw new BusinessException(ErrorCode.SOURCE_NOT_FOUND);
        }
    }

    private void ensureParentProjectActiveForWrite(Long userId, Source source) {
        try {
            researchProjectService.getRequiredActiveProjectForWrite(userId, source.getResearchProjectId());
        } catch (BusinessException ex) {
            throw new BusinessException(ErrorCode.SOURCE_NOT_FOUND);
        }
    }

    private SourceResponse toResponse(Source source) {
        Task latestTask = taskRepository.findTopByTaskTypeAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                        TaskType.SOURCE_IMPORT,
                        SOURCE_TARGET_TYPE,
                        source.getId()
                )
                .orElse(null);
        return toResponse(source, latestTask == null ? null : latestTask.getId());
    }

    private SourceResponse toResponse(Source source, Long taskId) {
        return SourceResponse.builder()
                .id(source.getId())
                .spaceId(source.getSpaceId())
                .researchProjectId(source.getResearchProjectId())
                .title(source.getTitle())
                .sourceType(source.getSourceType())
                .url(source.getUrl())
                .objectKey(source.getObjectKey())
                .rawTextObjectKey(source.getRawTextObjectKey())
                .parsedTextObjectKey(source.getParsedTextObjectKey())
                .contentHash(source.getContentHash())
                .importStatus(source.getImportStatus())
                .compileStatus(source.getCompileStatus())
                .tokenCount(source.getTokenCount())
                .errorMessage(source.getErrorMessage())
                .createdBy(source.getCreatedBy())
                .taskId(taskId)
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .build();
    }

    private BusinessException sourceAccessError(Long sourceId, Long personalSpaceId) {
        return sourceRepository.findById(sourceId)
                .filter(source -> source.getDeletedAt() == null)
                .map(source -> !source.getSpaceId().equals(personalSpaceId)
                        ? new BusinessException(ErrorCode.SOURCE_ACCESS_DENIED, "No permission to access this source")
                        : new BusinessException(ErrorCode.SOURCE_NOT_FOUND))
                .orElseGet(() -> new BusinessException(ErrorCode.SOURCE_NOT_FOUND));
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.STORAGE_OPERATION_FAILED, "Failed to read upload bytes");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private int countTokens(String text) {
        String normalized = normalizeOptional(text);
        if (normalized == null) {
            return 0;
        }
        return normalized.split("\\s+").length;
    }

    private String normalizeTitle(String title, String fallback) {
        String normalized = normalizeOptional(title);
        return normalized == null ? fallback : normalized;
    }

    private String normalizeFileName(String fileName) {
        String normalized = normalizeOptional(fileName);
        return normalized == null ? "source.txt" : normalized;
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

    private String normalizeUrl(String rawUrl) {
        try {
            java.net.URI parsed = java.net.URI.create(normalizeRequired(rawUrl));
            String scheme = parsed.getScheme() == null ? "http" : parsed.getScheme().toLowerCase();
            String host = parsed.getHost() == null ? null : parsed.getHost().toLowerCase();
            int port = parsed.getPort();
            String path = parsed.getPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            java.net.URI normalized = new java.net.URI(
                    scheme,
                    parsed.getUserInfo(),
                    host,
                    port,
                    path,
                    parsed.getQuery(),
                    null
            );
            return normalized.normalize().toString();
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "url: invalid url");
        }
    }
}
