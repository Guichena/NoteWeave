package com.noteweave.citation.service;

import com.noteweave.citation.dto.CitationResponse;
import com.noteweave.citation.model.Citation;
import com.noteweave.citation.model.MessageCitation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.citation.repository.MessageCitationRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.storage.config.StorageProperties;
import com.noteweave.storage.service.FileStorageService;
import com.noteweave.team.rag.evidence.EvidenceItem;
import com.noteweave.team.rag.evidence.EvidenceSource;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CitationService {

    private final CitationRepository citationRepository;
    private final MessageCitationRepository messageCitationRepository;
    private final ResourceAccessService resourceAccessService;
    private final FileStorageService fileStorageService;
    private final StorageProperties storageProperties;

    @Transactional
    public List<CitationResponse> saveForAssistantMessage(Long assistantMessageId, Long spaceId, List<EvidenceItem> evidenceItems) {
        try {
            List<CitationResponse> responses = new ArrayList<>();
            for (EvidenceItem item : evidenceItems) {
                for (EvidenceSource source : item.sources()) {
                    Citation citation = citationRepository.findBySpaceIdAndSourceTypeAndSourceIdAndChunkId(
                                    spaceId,
                                    "DOCUMENT",
                                    item.documentId(),
                                    source.chunkId()
                            )
                            .orElseGet(Citation::new);
                    citation.setSpaceId(spaceId);
                    citation.setSourceType("DOCUMENT");
                    citation.setSourceId(item.documentId());
                    citation.setChunkId(source.chunkId());
                    citation.setPageNo(source.pageNo() == null ? 1 : source.pageNo());
                    citation.setStartOffset(source.startOffset());
                    citation.setEndOffset(source.endOffset());
                    citation.setTitle(item.documentTitle());
                    citation.setQuoteText(source.quoteText());
                    citation.setQuoteHash(sha256(source.quoteText()));
                    citation.setLocationInfo("chunk " + source.chunkIndex());
                    citation.setSourceVersion(source.sourceVersion());
                    Citation saved = citationRepository.save(citation);
                    String snapshotObjectKey = storeSnapshot(saved.getId(), source.quoteText());
                    if (!snapshotObjectKey.equals(saved.getSnapshotObjectKey())) {
                        saved.setSnapshotObjectKey(snapshotObjectKey);
                        saved = citationRepository.save(saved);
                    }

                    if (messageCitationRepository.findByMessageIdAndCitationId(assistantMessageId, saved.getId()).isEmpty()) {
                        MessageCitation relation = new MessageCitation();
                        relation.setMessageId(assistantMessageId);
                        relation.setCitationId(saved.getId());
                        messageCitationRepository.save(relation);
                    }
                    responses.add(toResponse(saved, assistantMessageId));
                }
            }
            return responses;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.CITATION_SAVE_FAILED, "Failed to save citations");
        }
    }

    @Transactional(readOnly = true)
    public List<CitationResponse> listByMessage(Long userId, Long messageId, Long spaceId) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        return messageCitationRepository.findByMessageId(messageId).stream()
                .map(MessageCitation::getCitationId)
                .map(citationRepository::findById)
                .flatMap(java.util.Optional::stream)
                .peek(citation -> resourceAccessService.requireViewSpace(userId, citation.getSpaceId()))
                .map(citation -> toResponse(citation, messageId))
                .toList();
    }

    private CitationResponse toResponse(Citation citation, Long messageId) {
        return CitationResponse.builder()
                .id(citation.getId())
                .messageId(messageId)
                .spaceId(citation.getSpaceId())
                .sourceType(citation.getSourceType())
                .sourceId(citation.getSourceId())
                .chunkId(citation.getChunkId())
                .title(citation.getTitle())
                .quoteText(citation.getQuoteText())
                .locationInfo(citation.getLocationInfo())
                .pageNo(citation.getPageNo())
                .startOffset(citation.getStartOffset())
                .endOffset(citation.getEndOffset())
                .quoteHash(citation.getQuoteHash())
                .snapshotObjectKey(citation.getSnapshotObjectKey())
                .sourceVersion(citation.getSourceVersion())
                .createdAt(citation.getCreatedAt())
                .build();
    }

    private String storeSnapshot(Long citationId, String quoteText) {
        String objectKey = resolveObjectPrefix() + "/citations/" + citationId + "/snapshot.txt";
        byte[] bytes = (quoteText == null ? "" : quoteText).getBytes(StandardCharsets.UTF_8);
        fileStorageService.putObject(currentBucket(), objectKey, new ByteArrayInputStream(bytes), bytes.length, "text/plain; charset=utf-8");
        return objectKey;
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

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash citation quote", ex);
        }
    }
}
