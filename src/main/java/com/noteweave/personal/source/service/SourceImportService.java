package com.noteweave.personal.source.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.personal.project.model.ResearchProject;
import com.noteweave.personal.project.model.ResearchProjectStatus;
import com.noteweave.personal.project.repository.ResearchProjectRepository;
import com.noteweave.personal.project.service.ResearchProjectCompileStatusService;
import com.noteweave.personal.source.fetch.FetchedUrlContent;
import com.noteweave.personal.source.fetch.UrlContentFetcher;
import com.noteweave.personal.source.model.SourceCompileStatus;
import com.noteweave.personal.source.model.Source;
import com.noteweave.personal.source.model.SourceImportStatus;
import com.noteweave.personal.source.model.SourceType;
import com.noteweave.personal.source.repository.SourceRepository;
import com.noteweave.task.model.Task;
import com.noteweave.team.document.parser.DocumentParserService;
import com.noteweave.team.document.parser.ParseResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceImportService {

    private final ResearchProjectRepository researchProjectRepository;
    private final SourceRepository sourceRepository;
    private final SourceStorageSupport sourceStorageSupport;
    private final DocumentParserService documentParserService;
    private final UrlContentFetcher urlContentFetcher;
    private final ResearchProjectCompileStatusService researchProjectCompileStatusService;
    private final Tika tika = new Tika();

    public SourceImportService(
            ResearchProjectRepository researchProjectRepository,
            SourceRepository sourceRepository,
            SourceStorageSupport sourceStorageSupport,
            DocumentParserService documentParserService,
            UrlContentFetcher urlContentFetcher,
            ResearchProjectCompileStatusService researchProjectCompileStatusService,
            @Value("${noteweave.document.parsing.max-text-length:2000000}") int maxTextLength
    ) {
        this.researchProjectRepository = researchProjectRepository;
        this.sourceRepository = sourceRepository;
        this.sourceStorageSupport = sourceStorageSupport;
        this.documentParserService = documentParserService;
        this.urlContentFetcher = urlContentFetcher;
        this.researchProjectCompileStatusService = researchProjectCompileStatusService;
        this.tika.setMaxStringLength(maxTextLength);
    }

    @Transactional(noRollbackFor = Exception.class)
    public SourceImportResult importSource(Task task) {
        Source current = sourceRepository.findById(task.getTargetId()).orElse(null);
        if (current == null) {
            return skipped(null, "SOURCE_MISSING");
        }
        if (current.getDeletedAt() != null) {
            return skipped(current.getId(), "SOURCE_DELETED");
        }

        ResearchProject project = researchProjectRepository.findByIdForUpdate(current.getResearchProjectId())
                .orElse(null);
        if (project == null || project.getDeletedAt() != null || project.getStatus() != ResearchProjectStatus.ACTIVE) {
            return skipped(current.getId(), "RESEARCH_PROJECT_INACTIVE");
        }

        Source source = sourceRepository.findByIdForUpdate(task.getTargetId())
                .orElse(null);
        if (source == null) {
            return skipped(current.getId(), "SOURCE_MISSING");
        }
        if (source.getDeletedAt() != null) {
            return skipped(source.getId(), "SOURCE_DELETED");
        }
        if (!source.getResearchProjectId().equals(project.getId())) {
            return skipped(source.getId(), "SOURCE_PROJECT_CHANGED");
        }

        source.setImportStatus(SourceImportStatus.IMPORTING);
        source.setCompileStatus(SourceCompileStatus.PENDING);
        source.setErrorMessage(null);
        sourceRepository.save(source);
        researchProjectCompileStatusService.refresh(source.getResearchProjectId());

        try {
            int attempt = resolveAttempt(task);
            return switch (source.getSourceType()) {
                case TEXT -> importText(source);
                case FILE -> importFile(source, attempt);
                case URL -> importUrl(source, task.getUserId(), attempt);
            };
        } catch (Exception ex) {
            source.setImportStatus(SourceImportStatus.FAILED);
            source.setErrorMessage(safeMessage(ex));
            sourceRepository.save(source);
            researchProjectCompileStatusService.refresh(source.getResearchProjectId());
            throw ex;
        }
    }

    private SourceImportResult importText(Source source) {
        if (!sourceStorageSupport.objectExists(source.getRawTextObjectKey())) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Text source raw text object is missing");
        }
        String text = sourceStorageSupport.readTextObject(source.getRawTextObjectKey());
        markReady(source, source.getRawTextObjectKey(), source.getParsedTextObjectKey(), text, source.getContentHash());
        return success(source.getId(), false);
    }

    private SourceImportResult importFile(Source source, int attempt) {
        boolean freshAttempt = attempt > 1;
        if (!freshAttempt && sourceStorageSupport.objectExists(source.getParsedTextObjectKey())) {
            String text = sourceStorageSupport.readTextObject(source.getParsedTextObjectKey());
            markReady(source, source.getRawTextObjectKey(), source.getParsedTextObjectKey(), text, source.getContentHash());
            return success(source.getId(), false);
        }
        if (!sourceStorageSupport.objectExists(source.getObjectKey())) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Original file object is missing");
        }

        ParseResult parseResult;
        try (java.io.InputStream inputStream = sourceStorageSupport.getObject(source.getObjectKey())) {
            parseResult = documentParserService.parse(inputStream, sourceStorageSupport.fileNameFromObjectKey(source.getObjectKey()), null);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Failed to parse file source: " + ex.getMessage());
        }

        String text = normalizeText(parseResult.text());
        if (text == null) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Parsed file source text is empty");
        }

        String parsedTextObjectKey = sourceStorageSupport.parsedTextObjectKey(source.getId(), attempt);
        sourceStorageSupport.writeTextObject(parsedTextObjectKey, text);
        if (!sourceStorageSupport.objectExists(parsedTextObjectKey)) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Parsed text object was not created");
        }

        markReady(source, source.getRawTextObjectKey(), parsedTextObjectKey, text, source.getContentHash());
        return success(source.getId(), false);
    }

    private SourceImportResult importUrl(Source source, Long operatorId, int attempt) {
        urlContentFetcher.validate(source.getUrl());
        boolean freshAttempt = attempt > 1;
        String existingText = !freshAttempt && sourceStorageSupport.objectExists(source.getRawTextObjectKey())
                ? sourceStorageSupport.readTextObject(source.getRawTextObjectKey())
                : null;
        if (normalizeText(existingText) != null) {
            String contentHash = sha256(existingText.getBytes(StandardCharsets.UTF_8));
            SourceImportResult deduplicated = maybeDeduplicateUrlSource(source, operatorId, contentHash, existingText);
            if (deduplicated != null) {
                source.setContentHash(contentHash);
                source.setImportStatus(SourceImportStatus.READY);
                source.setTokenCount(countTokens(existingText));
                source.setDeletedAt(LocalDateTime.now());
                source.setDeletedBy(operatorId);
                source.setErrorMessage(null);
                sourceRepository.save(source);
                researchProjectCompileStatusService.refresh(source.getResearchProjectId());
                return deduplicated;
            }
            markReady(source, source.getRawTextObjectKey(), source.getParsedTextObjectKey(), existingText, contentHash);
            return success(source.getId(), false);
        }

        FetchedUrlContent fetched = urlContentFetcher.fetch(source.getUrl());
        String text = extractUrlText(fetched.body(), fetched.contentType(), source.getUrl());
        if (text == null) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "URL source text is empty");
        }

        String rawTextObjectKey = sourceStorageSupport.rawTextObjectKey(source.getId(), attempt);
        sourceStorageSupport.writeTextObject(rawTextObjectKey, text);
        if (!sourceStorageSupport.objectExists(rawTextObjectKey)) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "URL raw text object was not created");
        }

        String contentHash = sha256(text.getBytes(StandardCharsets.UTF_8));
        SourceImportResult deduplicated = maybeDeduplicateUrlSource(source, operatorId, contentHash, text);
        if (deduplicated != null) {
            source.setRawTextObjectKey(rawTextObjectKey);
            source.setContentHash(contentHash);
            source.setImportStatus(SourceImportStatus.READY);
            source.setTokenCount(countTokens(text));
            source.setDeletedAt(LocalDateTime.now());
            source.setDeletedBy(operatorId);
            source.setErrorMessage(null);
            sourceRepository.save(source);
            researchProjectCompileStatusService.refresh(source.getResearchProjectId());
            return deduplicated;
        }

        markReady(source, rawTextObjectKey, source.getParsedTextObjectKey(), text, contentHash);
        return success(source.getId(), false);
    }

    private SourceImportResult maybeDeduplicateUrlSource(Source source, Long operatorId, String contentHash, String text) {
        Source canonical = sourceRepository.findFirstByResearchProjectIdAndDeletedAtIsNullAndContentHashAndIdNotOrderByCreatedAtAsc(
                        source.getResearchProjectId(),
                        contentHash,
                        source.getId()
                )
                .orElse(null);
        if (canonical == null) {
            return null;
        }
        Map<String, Object> output = new HashMap<>();
        output.put("sourceId", source.getId());
        output.put("canonicalSourceId", canonical.getId());
        output.put("deduplicated", true);
        output.put("tokenCount", countTokens(text));
        return new SourceImportResult(canonical.getId(), output);
    }

    private void markReady(Source source, String rawTextObjectKey, String parsedTextObjectKey, String text, String contentHash) {
        if (normalizeText(text) == null) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Source text is empty");
        }
        if ((rawTextObjectKey == null || !sourceStorageSupport.objectExists(rawTextObjectKey))
                && (parsedTextObjectKey == null || !sourceStorageSupport.objectExists(parsedTextObjectKey))) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "READY source must have readable raw or parsed text");
        }
        source.setRawTextObjectKey(rawTextObjectKey);
        source.setParsedTextObjectKey(parsedTextObjectKey);
        source.setContentHash(contentHash);
        source.setImportStatus(SourceImportStatus.READY);
        source.setTokenCount(countTokens(text));
        source.setErrorMessage(null);
        sourceRepository.save(source);
        researchProjectCompileStatusService.refresh(source.getResearchProjectId());
    }

    private SourceImportResult success(Long sourceId, boolean deduplicated) {
        Map<String, Object> output = new HashMap<>();
        output.put("sourceId", sourceId);
        output.put("deduplicated", deduplicated);
        return new SourceImportResult(sourceId, output);
    }

    private SourceImportResult skipped(Long sourceId, String reason) {
        Map<String, Object> output = new HashMap<>();
        output.put("skipped", true);
        output.put("reason", reason);
        return new SourceImportResult(sourceId, output);
    }

    private int resolveAttempt(Task task) {
        String marker = ":attempt:";
        String idempotencyKey = task.getIdempotencyKey();
        int index = idempotencyKey == null ? -1 : idempotencyKey.lastIndexOf(marker);
        if (index < 0) {
            return 1;
        }
        try {
            return Integer.parseInt(idempotencyKey.substring(index + marker.length()));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private String extractUrlText(byte[] bytes, String contentType, String url) {
        try {
            String normalizedType = contentType == null ? "" : contentType.toLowerCase();
            if (normalizedType.startsWith("text/plain") || normalizedType.startsWith("text/markdown")) {
                return normalizeText(new String(bytes, StandardCharsets.UTF_8));
            }
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, url);
            String text = tika.parseToString(new ByteArrayInputStream(bytes), metadata);
            return normalizeText(text);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "Failed to extract url text: " + ex.getMessage());
        }
    }

    private String normalizeText(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 1000 ? message.substring(0, 1000) : message;
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
        String normalized = normalizeText(text);
        if (normalized == null) {
            return 0;
        }
        return normalized.split("\\s+").length;
    }

    public record SourceImportResult(Long resultSourceId, Map<String, Object> output) {
    }
}
