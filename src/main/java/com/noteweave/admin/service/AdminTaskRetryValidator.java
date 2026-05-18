package com.noteweave.admin.service;

import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.project.model.ResearchProjectStatus;
import com.noteweave.personal.project.repository.ResearchProjectRepository;
import com.noteweave.personal.source.model.SourceImportStatus;
import com.noteweave.personal.source.repository.SourceRepository;
import com.noteweave.rageval.repository.RagEvalRunRepository;
import com.noteweave.storage.service.FileStorageService;
import com.noteweave.task.model.Task;
import com.noteweave.task.model.TaskType;
import com.noteweave.team.document.model.Document;
import com.noteweave.team.document.model.DocumentStatus;
import com.noteweave.team.document.repository.DocumentRepository;
import com.noteweave.team.wiki.model.WikiPageStatus;
import com.noteweave.team.wiki.repository.WikiPageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminTaskRetryValidator {

    private final DocumentRepository documentRepository;
    private final ArtifactRepository artifactRepository;
    private final SourceRepository sourceRepository;
    private final ResearchProjectRepository researchProjectRepository;
    private final WikiPageRepository wikiPageRepository;
    private final RagEvalRunRepository ragEvalRunRepository;
    private final FileStorageService fileStorageService;
    private final AdminStorageSupport adminStorageSupport;

    public void validate(Task task) {
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND);
        }
        switch (task.getTaskType()) {
            case DOCUMENT_PROCESS, DOCUMENT_REINDEX, EMBEDDING_BACKFILL -> validateDocumentTask(task);
            case ARTIFACT_GENERATE -> validateArtifactTask(task);
            case SOURCE_IMPORT, SOURCE_COMPILE -> validateSourceTask(task);
            case WIKI_INDEX -> validateWikiTask(task);
            case RAG_EVAL_RUN -> validateEvalRunTask(task);
            case CLEANUP_RESOURCE -> {
                // Cleanup tasks are system remediation tasks and can always be re-dispatched.
            }
            default -> throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Task type does not support admin retry");
        }
    }

    private void validateDocumentTask(Task task) {
        Document document = documentRepository.findById(task.getTargetId()).orElse(null);
        if (document == null || document.getDeletedAt() != null || document.getStatus() == DocumentStatus.DELETED) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Target document is no longer indexable");
        }
        if ((task.getTaskType() == TaskType.DOCUMENT_PROCESS || task.getTaskType() == TaskType.DOCUMENT_REINDEX)
                && (document.getObjectKey() == null || !fileStorageService.objectExists(adminStorageSupport.currentBucket(), document.getObjectKey()))) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Document source object is missing");
        }
        if (task.getTaskType() == TaskType.EMBEDDING_BACKFILL
                && (document.getStatus() != DocumentStatus.INDEXED || document.getActiveIndexVersion() <= 0)) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Document is not ready for embedding backfill");
        }
    }

    private void validateArtifactTask(Task task) {
        if (artifactRepository.findById(task.getTargetId()).filter(artifact -> artifact.getDeletedAt() == null).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Target artifact no longer exists");
        }
    }

    private void validateSourceTask(Task task) {
        var source = sourceRepository.findById(task.getTargetId()).orElse(null);
        if (source == null || source.getDeletedAt() != null) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Source no longer exists");
        }
        if (researchProjectRepository.findById(source.getResearchProjectId())
                .filter(project -> project.getDeletedAt() == null && project.getStatus() == ResearchProjectStatus.ACTIVE)
                .isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Source research project is inactive");
        }
        if (task.getTaskType() == TaskType.SOURCE_COMPILE && source.getImportStatus() != SourceImportStatus.READY) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Source import is not ready");
        }
    }

    private void validateWikiTask(Task task) {
        if (wikiPageRepository.findById(task.getTargetId())
                .filter(page -> page.getDeletedAt() == null && page.getStatus() == WikiPageStatus.PUBLISHED)
                .isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "Wiki page is no longer publishable");
        }
    }

    private void validateEvalRunTask(Task task) {
        if (ragEvalRunRepository.findById(task.getTargetId()).isEmpty()) {
            throw new BusinessException(ErrorCode.TASK_RETRY_NOT_ALLOWED, "RAG eval run no longer exists");
        }
    }
}
