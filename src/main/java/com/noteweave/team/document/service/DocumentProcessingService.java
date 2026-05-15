package com.noteweave.team.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.search.document.EsDocumentChunk;
import com.noteweave.search.service.SearchIndexService;
import com.noteweave.storage.config.StorageProperties;
import com.noteweave.storage.service.FileStorageService;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskAttempt;
import com.noteweave.task.model.TaskEventType;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.repository.TaskAttemptRepository;
import com.noteweave.task.repository.TaskRepository;
import com.noteweave.task.service.TaskEventService;
import com.noteweave.team.document.chunk.ChunkCandidate;
import com.noteweave.team.document.chunk.ChunkService;
import com.noteweave.team.document.dto.DocumentProcessTaskPayload;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentChunk;
import com.noteweave.team.document.model.DocumentStatus;
import com.noteweave.team.document.parser.DocumentParserService;
import com.noteweave.team.document.parser.ParseResult;
import com.noteweave.team.document.repository.DocumentRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final TaskRepository taskRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final TaskEventService taskEventService;
    private final DocumentRepository documentRepository;
    private final DocumentParserService documentParserService;
    private final ChunkService chunkService;
    private final DocumentChunkService documentChunkService;
    private final SearchIndexService searchIndexService;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public void process(DocumentProcessTaskPayload payload) {
        Long taskId = payload == null ? null : payload.getTaskId();
        if (taskId == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "document process payload missing taskId");
        }
        ClaimedDocument claimed = claimTask(taskId);
        if (claimed == null) {
            return;
        }

        try {
            ParseInput parseInput = parseInput(claimed.task());
            ParseResult parseResult;
            try (InputStream inputStream = fileStorageService.getObject(currentBucket(), claimed.document().getObjectKey())) {
                parseResult = documentParserService.parse(inputStream, parseInput.fileName(), parseInput.contentType());
            }
            List<ChunkCandidate> candidates = chunkService.split(parseResult.text());
            String parsedTextObjectKey = storeParsedText(claimed.document().getId(), claimed.indexVersion(), parseResult.text());
            List<DocumentChunk> chunks = documentChunkService.createChunks(claimed.document(), claimed.indexVersion(), candidates);
            searchIndexService.bulkIndexChunks(chunks.stream()
                    .map(chunk -> toEsChunk(claimed.document(), chunk))
                    .toList());
            markSuccess(claimed.task().getId(), claimed.document().getId(), claimed.attemptNo(), claimed.indexVersion(), parsedTextObjectKey, chunks);
        } catch (Exception ex) {
            log.warn("Document processing failed for task {} document {}", claimed.task().getId(), claimed.document().getId(), ex);
            markFailed(claimed.task().getId(), claimed.document().getId(), claimed.attemptNo(), ex);
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Document processing failed", ex);
        }
    }

    private ClaimedDocument claimTask(Long taskId) {
        return transactionTemplate.execute(status -> {
            Task task = taskRepository.findByIdForUpdate(taskId).orElse(null);
            if (task == null) {
                return null;
            }
            Long documentId = resolveDocumentId(task);
            if (documentId == null) {
                failPendingTask(task, "Task target document is missing");
                return null;
            }
            Document document = documentRepository.findByIdForUpdate(documentId).orElse(null);
            if (document == null) {
                failPendingTask(task, "Document not found");
                return null;
            }
            if (task.getTaskStatus() == TaskStatus.SUCCESS && documentChunkService.activeChunksExist(document.getId(), document.getActiveIndexVersion())) {
                return null;
            }
            if (task.getTaskStatus() != TaskStatus.PENDING) {
                return null;
            }

            int attemptNo = task.getRetryCount() + 1;
            LocalDateTime now = LocalDateTime.now();
            task.setTaskStatus(TaskStatus.RUNNING);
            task.setStartedAt(now);
            task.setFinishedAt(null);
            task.setErrorMessage(null);
            taskRepository.save(task);

            TaskAttempt attempt = new TaskAttempt();
            attempt.setTaskId(task.getId());
            attempt.setAttemptNo(attemptNo);
            attempt.setWorkerId("DOCUMENT_PROCESS-local-worker");
            attempt.setStatus(TaskStatus.RUNNING);
            attempt.setStartedAt(now);
            taskAttemptRepository.save(attempt);
            taskEventService.appendEvent(task.getId(), TaskEventType.TASK_STARTED, TaskStatus.PENDING, TaskStatus.RUNNING, "Document processing started", null, null);

            if (document.getDeletedAt() != null || document.getStatus() == DocumentStatus.DELETED) {
                task.setTaskStatus(TaskStatus.SUCCESS);
                task.setFinishedAt(LocalDateTime.now());
                task.setOutputJson(writeJson(Map.of("skipped", true, "reason", "DOCUMENT_DELETED")));
                taskRepository.save(task);
                attempt.setStatus(TaskStatus.SUCCESS);
                attempt.setFinishedAt(task.getFinishedAt());
                taskAttemptRepository.save(attempt);
                taskEventService.appendEvent(task.getId(), TaskEventType.TASK_SUCCEEDED, TaskStatus.RUNNING, TaskStatus.SUCCESS, "Document was deleted; processing skipped", null, null);
                return null;
            }

            int nextVersion = Math.max(document.getActiveIndexVersion(), 0) + 1;
            document.setStatus(DocumentStatus.PROCESSING);
            document.setParseStatus("PARSING");
            document.setIndexStatus("PENDING");
            document.setErrorMessage(null);
            documentRepository.save(document);
            return new ClaimedDocument(copyTask(task), copyDocument(document), attemptNo, nextVersion);
        });
    }

    private void markSuccess(Long taskId, Long documentId, int attemptNo, int indexVersion, String parsedTextObjectKey, List<DocumentChunk> chunks) {
        transactionTemplate.executeWithoutResult(status -> {
            Task task = requiredTaskForUpdate(taskId);
            Document document = documentRepository.findByIdForUpdate(documentId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
            int tokenCount = chunks.stream().mapToInt(DocumentChunk::getTokenCount).sum();
            document.setActiveIndexVersion(indexVersion);
            document.setParsedTextObjectKey(parsedTextObjectKey);
            document.setParseStatus("SUCCESS");
            document.setIndexStatus("SUCCESS");
            document.setStatus(DocumentStatus.INDEXED);
            document.setChunkCount(chunks.size());
            document.setTokenCount(tokenCount);
            document.setErrorMessage(null);
            documentRepository.save(document);
            searchIndexService.synchronizeDocumentChunkState(documentId, document.getStatus().name(), document.getActiveIndexVersion(), "ACTIVE");

            TaskStatus fromStatus = task.getTaskStatus();
            task.setTaskStatus(TaskStatus.SUCCESS);
            task.setCancelRequested(false);
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(null);
            task.setResultRefType("DOCUMENT");
            task.setResultRefId(documentId);
            task.setOutputJson(writeJson(Map.of(
                    "documentId", documentId,
                    "indexVersion", indexVersion,
                    "chunkCount", chunks.size(),
                    "parsedTextObjectKey", parsedTextObjectKey
            )));
            taskRepository.save(task);

            TaskAttempt attempt = requiredAttempt(taskId, attemptNo);
            attempt.setStatus(TaskStatus.SUCCESS);
            attempt.setFinishedAt(task.getFinishedAt());
            attempt.setErrorCode(null);
            attempt.setErrorMessage(null);
            taskAttemptRepository.save(attempt);
            taskEventService.appendEvent(taskId, TaskEventType.TASK_SUCCEEDED, fromStatus, TaskStatus.SUCCESS, "Document indexed", null, null);
        });
    }

    private void markFailed(Long taskId, Long documentId, int attemptNo, Exception ex) {
        transactionTemplate.executeWithoutResult(status -> {
            String message = safeMessage(ex);
            Task task = requiredTaskForUpdate(taskId);
            TaskStatus fromStatus = task.getTaskStatus();
            task.setTaskStatus(TaskStatus.FAILED);
            task.setCancelRequested(false);
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(message);
            taskRepository.save(task);

            documentRepository.findByIdForUpdate(documentId).ifPresent(document -> {
                if (document.getStatus() != DocumentStatus.DELETED) {
                    if (document.getActiveIndexVersion() > 0 && documentChunkService.activeChunksExist(document.getId(), document.getActiveIndexVersion())) {
                        document.setStatus(DocumentStatus.INDEXED);
                        document.setParseStatus("SUCCESS");
                        document.setIndexStatus("SUCCESS");
                    } else {
                        document.setStatus(DocumentStatus.FAILED);
                        document.setParseStatus("FAILED");
                        document.setIndexStatus("FAILED");
                    }
                    document.setErrorMessage(message);
                    documentRepository.save(document);
                    searchIndexService.synchronizeDocumentChunkState(document.getId(), document.getStatus().name(), document.getActiveIndexVersion(), "ACTIVE");
                }
            });

            TaskAttempt attempt = requiredAttempt(taskId, attemptNo);
            attempt.setStatus(TaskStatus.FAILED);
            attempt.setFinishedAt(task.getFinishedAt());
            attempt.setErrorCode(ex instanceof BusinessException businessException ? businessException.getErrorCode().name() : ex.getClass().getSimpleName());
            attempt.setErrorMessage(message);
            taskAttemptRepository.save(attempt);
            taskEventService.appendEvent(taskId, TaskEventType.TASK_FAILED, fromStatus, TaskStatus.FAILED, message, null, null);
        });
    }

    private void failPendingTask(Task task, String message) {
        if (task.getTaskStatus() != TaskStatus.PENDING) {
            return;
        }
        task.setTaskStatus(TaskStatus.FAILED);
        task.setFinishedAt(LocalDateTime.now());
        task.setErrorMessage(message);
        taskRepository.save(task);
        taskEventService.appendEvent(task.getId(), TaskEventType.TASK_FAILED, TaskStatus.PENDING, TaskStatus.FAILED, message, null, null);
    }

    private EsDocumentChunk toEsChunk(Document document, DocumentChunk chunk) {
        return EsDocumentChunk.builder()
                .esDocId(chunk.getEsDocId())
                .spaceId(chunk.getSpaceId())
                .knowledgeBaseId(chunk.getKnowledgeBaseId())
                .documentId(chunk.getDocumentId())
                .documentStatus(document.getStatus().name())
                .indexVersion(chunk.getIndexVersion())
                .activeIndexVersion(chunk.getIndexVersion())
                .chunkId(chunk.getId())
                .chunkIndex(chunk.getChunkIndex())
                .title(document.getTitle())
                .content(chunk.getContent())
                .contentHash(chunk.getContentHash())
                .sourceType(document.getSourceType())
                .createdBy(document.getCreatedBy())
                .lifecycleStatus("ACTIVE")
                .createdAt(chunk.getCreatedAt())
                .build();
    }

    private String storeParsedText(Long documentId, int indexVersion, String text) {
        String objectKey = parsedTextObjectKey(documentId, indexVersion);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        fileStorageService.putObject(currentBucket(), objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/plain; charset=utf-8");
        return objectKey;
    }

    private String parsedTextObjectKey(Long documentId, int indexVersion) {
        return resolveObjectPrefix() + "/parsed-text/document/" + documentId + "/" + indexVersion + ".txt";
    }

    private ParseInput parseInput(Task task) {
        try {
            JsonNode input = objectMapper.readTree(task.getInputJson());
            return new ParseInput(
                    text(input, "fileName", null),
                    text(input, "contentType", null)
            );
        } catch (Exception ex) {
            return new ParseInput(null, null);
        }
    }

    private Long resolveDocumentId(Task task) {
        if (task.getTargetId() != null && "DOCUMENT".equals(task.getTargetType())) {
            return task.getTargetId();
        }
        try {
            JsonNode input = objectMapper.readTree(task.getInputJson());
            long documentId = input.path("documentId").asLong(0);
            return documentId > 0 ? documentId : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field)) {
            return fallback;
        }
        String value = node.path(field).asText();
        return value.isBlank() ? fallback : value;
    }

    private String currentBucket() {
        if (normalizeTestRunId(storageProperties.paths().testRunId()) != null) {
            return fileStorageService.testBucket();
        }
        return fileStorageService.devBucket();
    }

    private String resolveObjectPrefix() {
        String configuredTestRunId = normalizeTestRunId(storageProperties.paths().testRunId());
        if (configuredTestRunId != null) {
            return storageProperties.paths().testObjectPrefix() + "/" + configuredTestRunId;
        }
        return storageProperties.paths().devObjectPrefix();
    }

    private String normalizeTestRunId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private Task requiredTaskForUpdate(Long taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }

    private TaskAttempt requiredAttempt(Long taskId, int attemptNo) {
        return taskAttemptRepository.findByTaskIdAndAttemptNo(taskId, attemptNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND, "Task attempt not found"));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize task output", ex);
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private Task copyTask(Task source) {
        Task task = new Task();
        task.setId(source.getId());
        task.setUserId(source.getUserId());
        task.setSpaceId(source.getSpaceId());
        task.setResearchProjectId(source.getResearchProjectId());
        task.setTaskType(source.getTaskType());
        task.setTargetType(source.getTargetType());
        task.setTargetId(source.getTargetId());
        task.setTaskStatus(source.getTaskStatus());
        task.setIdempotencyKey(source.getIdempotencyKey());
        task.setInputJson(source.getInputJson());
        task.setRetryCount(source.getRetryCount());
        task.setMaxRetryCount(source.getMaxRetryCount());
        return task;
    }

    private Document copyDocument(Document source) {
        Document document = new Document();
        document.setId(source.getId());
        document.setSpaceId(source.getSpaceId());
        document.setKnowledgeBaseId(source.getKnowledgeBaseId());
        document.setFileObjectId(source.getFileObjectId());
        document.setTitle(source.getTitle());
        document.setSourceType(source.getSourceType());
        document.setObjectKey(source.getObjectKey());
        document.setOriginalFilename(source.getOriginalFilename());
        document.setContentHash(source.getContentHash());
        document.setActiveIndexVersion(source.getActiveIndexVersion());
        document.setCreatedBy(source.getCreatedBy());
        return document;
    }

    private record ClaimedDocument(Task task, Document document, int attemptNo, int indexVersion) {
    }

    private record ParseInput(String fileName, String contentType) {
    }
}
