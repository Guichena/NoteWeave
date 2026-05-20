package com.noteweave.team.wiki.service;

import com.noteweave.artifact.model.Artifact;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactVersion;
import com.noteweave.artifact.repository.ArtifactRepository;
import com.noteweave.artifact.repository.ArtifactVersionRepository;
import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.repository.ChatMessageRepository;
import com.noteweave.chat.service.ChatSessionService;
import com.noteweave.citation.model.MessageCitation;
import com.noteweave.citation.repository.CitationRepository;
import com.noteweave.citation.repository.MessageCitationRepository;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.permission.service.ResourceAccessService;
import com.noteweave.task.dto.TaskResponse;
import com.noteweave.task.model.TaskType;
import com.noteweave.task.service.TaskCreateCommand;
import com.noteweave.task.service.TaskService;
import com.noteweave.team.wiki.dto.CreateWikiDraftFromMessageRequest;
import com.noteweave.team.wiki.dto.CreateWikiDraftRequest;
import com.noteweave.team.wiki.dto.PublishArtifactToWikiRequest;
import com.noteweave.team.wiki.dto.PublishWikiPageRequest;
import com.noteweave.team.wiki.dto.UpdateWikiPageRequest;
import com.noteweave.team.wiki.dto.WikiPageResponse;
import com.noteweave.team.wiki.dto.WikiPageVersionResponse;
import com.noteweave.team.wiki.model.WikiIndexStatus;
import com.noteweave.team.wiki.model.WikiPage;
import com.noteweave.team.wiki.model.WikiPageCitation;
import com.noteweave.team.wiki.model.WikiPageStatus;
import com.noteweave.team.wiki.model.WikiPageVersion;
import com.noteweave.team.wiki.repository.WikiPageCitationRepository;
import com.noteweave.team.wiki.repository.WikiPageRepository;
import com.noteweave.team.wiki.repository.WikiPageVersionRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class TeamWikiService {

    private static final String WIKI_PAGE_TARGET_TYPE = "WIKI_PAGE";

    private final WikiPageRepository wikiPageRepository;
    private final WikiPageVersionRepository wikiPageVersionRepository;
    private final WikiPageCitationRepository wikiPageCitationRepository;
    private final ArtifactRepository artifactRepository;
    private final ArtifactVersionRepository artifactVersionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionService chatSessionService;
    private final MessageCitationRepository messageCitationRepository;
    private final CitationRepository citationRepository;
    private final ResourceAccessService resourceAccessService;
    private final TaskService taskService;
    private final ObjectProvider<WikiIndexService> wikiIndexServiceProvider;
    private final WikiGraphSyncService wikiGraphSyncService;

    @Transactional
    public WikiPageResponse createDraft(Long userId, Long spaceId, CreateWikiDraftRequest request) {
        resourceAccessService.requireEditWiki(userId, spaceId);
        WikiPage page = new WikiPage();
        page.setSpaceId(spaceId);
        page.setTitle(normalizeRequired(request.getTitle()));
        page.setContent(normalizeRequired(request.getContent()));
        page.setStatus(WikiPageStatus.DRAFT);
        page.setSourceArtifactId(request.getSourceArtifactId());
        page.setIndexStatus(WikiIndexStatus.PENDING);
        page.setCreatedBy(userId);
        page.setUpdatedBy(userId);
        WikiPage saved = wikiPageRepository.save(page);
        rebuildGraph(spaceId);
        return toResponse(saved);
    }

    @Transactional
    public WikiPageResponse createDraftFromArtifact(Long userId, Long artifactId, PublishArtifactToWikiRequest request) {
        Artifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND));
        if (artifact.getDeletedAt() != null || artifact.getStatus() == ArtifactStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        resourceAccessService.requireEditWiki(userId, request.getSpaceId());
        if (!artifact.getSpaceId().equals(request.getSpaceId())) {
            throw new BusinessException(ErrorCode.ARTIFACT_CANNOT_PUBLISH_TO_WIKI, "artifact is outside the requested space");
        }
        resourceAccessService.requireViewSpace(userId, artifact.getSpaceId());

        ArtifactVersion artifactVersion = resolveArtifactVersion(artifact, request.getArtifactVersionId());
        WikiPage page = new WikiPage();
        page.setSpaceId(request.getSpaceId());
        page.setTitle(normalizeRequired(request.getTitle()));
        page.setContent(normalizeRequired(artifactVersion.getContent()));
        page.setStatus(WikiPageStatus.DRAFT);
        page.setSourceArtifactId(artifact.getId());
        page.setIndexStatus(WikiIndexStatus.PENDING);
        page.setCreatedBy(userId);
        page.setUpdatedBy(userId);
        WikiPage saved = wikiPageRepository.save(page);
        rebuildGraph(request.getSpaceId());
        return toResponse(saved);
    }

    @Transactional
    public WikiPageResponse createDraftFromMessage(Long userId, Long messageId, CreateWikiDraftFromMessageRequest request) {
        ChatMessage message = chatSessionService.getRequiredMessage(userId, messageId);
        Long spaceId = chatSessionService.getSessionByMessageId(userId, messageId).getSpaceId();
        resourceAccessService.requireEditWiki(userId, spaceId);

        WikiPage page = new WikiPage();
        page.setSpaceId(spaceId);
        page.setTitle(normalizeRequired(request.getTitle()));
        page.setContent(normalizeRequired(message.getContent()));
        page.setStatus(WikiPageStatus.DRAFT);
        page.setSourceMessageId(messageId);
        page.setIndexStatus(WikiIndexStatus.PENDING);
        page.setCreatedBy(userId);
        page.setUpdatedBy(userId);
        WikiPage saved = wikiPageRepository.save(page);
        rebuildGraph(spaceId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<WikiPageResponse> list(Long userId, Long spaceId) {
        resourceAccessService.requireViewSpace(userId, spaceId);
        return wikiPageRepository.findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(spaceId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WikiPageResponse get(Long userId, Long pageId) {
        WikiPage page = getRequiredReadablePage(userId, pageId);
        return toResponse(page);
    }

    @Transactional
    public WikiPageResponse updateDraft(Long userId, Long pageId, UpdateWikiPageRequest request) {
        WikiPage page = getRequiredWritableDraft(userId, pageId);
        page.setTitle(normalizeRequired(request.getTitle()));
        page.setContent(normalizeRequired(request.getContent()));
        page.setUpdatedBy(userId);
        WikiPage saved = wikiPageRepository.save(page);
        rebuildGraph(saved.getSpaceId());
        return toResponse(saved);
    }

    @Transactional
    public WikiPageResponse publish(Long userId, Long pageId, PublishWikiPageRequest request) {
        WikiPage page = wikiPageRepository.findByIdForUpdate(pageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND));
        if (page.getDeletedAt() != null || page.getStatus() == WikiPageStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND);
        }
        resourceAccessService.requirePublishWiki(userId, page.getSpaceId());
        if (page.getStatus() != WikiPageStatus.DRAFT) {
            throw new BusinessException(ErrorCode.WIKI_DRAFT_REQUIRED);
        }

        int nextVersionNo = wikiPageVersionRepository.findMaxVersionNo(page.getId()) + 1;
        WikiPageVersion version = new WikiPageVersion();
        version.setWikiPageId(page.getId());
        version.setVersionNo(nextVersionNo);
        version.setTitle(page.getTitle());
        version.setContent(page.getContent());
        version.setChangeNote(normalizeNullable(request == null ? null : request.getChangeNote()));
        version.setCreatedBy(userId);
        version = wikiPageVersionRepository.save(version);

        page.setStatus(WikiPageStatus.PUBLISHED);
        page.setPublishedVersionId(version.getId());
        page.setIndexStatus(WikiIndexStatus.PENDING);
        page.setUpdatedBy(userId);
        page = wikiPageRepository.save(page);

        copyMessageCitationsIfNeeded(page, version);

        TaskResponse task = taskService.createTask(TaskCreateCommand.builder()
                .userId(userId)
                .spaceId(page.getSpaceId())
                .taskType(TaskType.WIKI_INDEX)
                .targetType(WIKI_PAGE_TARGET_TYPE)
                .targetId(page.getId())
                .idempotencyKey(buildWikiIndexIdempotencyKey(page.getId(), version.getId()))
                .input(buildWikiIndexInput(page, version))
                .build());

        if (page.getSourceArtifactId() != null) {
            artifactRepository.findById(page.getSourceArtifactId()).ifPresent(artifact -> {
                artifact.setStatus(ArtifactStatus.PUBLISHED_TO_WIKI);
                artifactRepository.save(artifact);
            });
        }

        // keep task referenced to avoid "unused" at compile time and to make future debugging easier
        if (task.getId() == null) {
            throw new BusinessException(ErrorCode.WIKI_PUBLISH_FAILED, "failed to create wiki index task");
        }
        rebuildGraph(page.getSpaceId());
        return toResponse(page);
    }

    @Transactional
    public void archive(Long userId, Long pageId) {
        WikiPage page = wikiPageRepository.findByIdForUpdate(pageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND));
        if (page.getDeletedAt() != null || page.getStatus() == WikiPageStatus.ARCHIVED) {
            return;
        }
        resourceAccessService.requirePublishWiki(userId, page.getSpaceId());
        page.setStatus(WikiPageStatus.ARCHIVED);
        page.setDeletedAt(LocalDateTime.now());
        page.setDeletedBy(userId);
        page.setUpdatedBy(userId);
        wikiPageRepository.save(page);
        rebuildGraph(page.getSpaceId());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            Long wikiPageId = page.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wikiIndexServiceProvider.ifAvailable(service -> service.remove(wikiPageId));
                }
            });
        }
    }

    @Transactional(readOnly = true)
    public List<WikiPageVersionResponse> listVersions(Long userId, Long pageId) {
        WikiPage page = getRequiredReadablePage(userId, pageId);
        return wikiPageVersionRepository.findByWikiPageIdOrderByVersionNoAsc(page.getId()).stream()
                .map(this::toVersionResponse)
                .toList();
    }

    @Transactional
    public void markIndexed(Long pageId) {
        wikiPageRepository.findByIdForUpdate(pageId).ifPresent(page -> {
            page.setIndexStatus(WikiIndexStatus.INDEXED);
            wikiPageRepository.save(page);
        });
    }

    @Transactional
    public void markIndexFailed(Long pageId) {
        wikiPageRepository.findByIdForUpdate(pageId).ifPresent(page -> {
            page.setIndexStatus(WikiIndexStatus.FAILED);
            wikiPageRepository.save(page);
        });
    }

    @Transactional(readOnly = true)
    public WikiPage getRequiredReadablePage(Long userId, Long pageId) {
        WikiPage page = wikiPageRepository.findByIdAndDeletedAtIsNull(pageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND));
        if (!resourceAccessService.canViewSpace(userId, page.getSpaceId())) {
            throw new BusinessException(ErrorCode.WIKI_ACCESS_DENIED, "No permission to view wiki");
        }
        return page;
    }

    @Transactional(readOnly = true)
    public List<WikiPage> listPublishedIndexedPages(Long spaceId) {
        return wikiPageRepository.findBySpaceIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(spaceId, WikiPageStatus.PUBLISHED).stream()
                .filter(page -> page.getIndexStatus() == WikiIndexStatus.INDEXED)
                .toList();
    }

    @Transactional(readOnly = true)
    public WikiPageVersion getRequiredPublishedVersion(Long pageId, Long publishedVersionId) {
        WikiPage page = wikiPageRepository.findByIdAndDeletedAtIsNull(pageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND));
        if (page.getStatus() != WikiPageStatus.PUBLISHED || !publishedVersionId.equals(page.getPublishedVersionId())) {
            throw new BusinessException(ErrorCode.WIKI_INDEX_FAILED, "wiki page version is no longer current");
        }
        return wikiPageVersionRepository.findById(publishedVersionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<WikiPageCitation> listVersionCitations(Long wikiPageId, Long wikiPageVersionId) {
        return wikiPageCitationRepository.findByWikiPageIdAndWikiPageVersionIdOrderByIdAsc(wikiPageId, wikiPageVersionId);
    }

    private void copyMessageCitationsIfNeeded(WikiPage page, WikiPageVersion version) {
        if (page.getSourceMessageId() == null) {
            return;
        }
        List<MessageCitation> relations = messageCitationRepository.findByMessageId(page.getSourceMessageId());
        for (MessageCitation relation : relations) {
            citationRepository.findById(relation.getCitationId()).ifPresent(citation -> wikiPageCitationRepository
                    .findByWikiPageIdAndWikiPageVersionIdAndCitationIdAndRelationType(page.getId(), version.getId(), citation.getId(), "EVIDENCE")
                    .orElseGet(() -> {
                        WikiPageCitation link = new WikiPageCitation();
                        link.setWikiPageId(page.getId());
                        link.setWikiPageVersionId(version.getId());
                        link.setCitationId(citation.getId());
                        link.setRelationType("EVIDENCE");
                        return wikiPageCitationRepository.save(link);
                    }));
        }
    }

    private WikiPageResponse toResponse(WikiPage page) {
        Integer publishedVersionNo = null;
        if (page.getPublishedVersionId() != null) {
            publishedVersionNo = wikiPageVersionRepository.findById(page.getPublishedVersionId())
                    .map(WikiPageVersion::getVersionNo)
                    .orElse(null);
        }
        return WikiPageResponse.builder()
                .id(page.getId())
                .spaceId(page.getSpaceId())
                .title(page.getTitle())
                .content(page.getContent())
                .status(page.getStatus())
                .sourceArtifactId(page.getSourceArtifactId())
                .sourceMessageId(page.getSourceMessageId())
                .publishedVersionId(page.getPublishedVersionId())
                .publishedVersionNo(publishedVersionNo)
                .indexStatus(page.getIndexStatus())
                .createdBy(page.getCreatedBy())
                .updatedBy(page.getUpdatedBy())
                .createdAt(page.getCreatedAt())
                .updatedAt(page.getUpdatedAt())
                .build();
    }

    private WikiPageVersionResponse toVersionResponse(WikiPageVersion version) {
        return WikiPageVersionResponse.builder()
                .id(version.getId())
                .wikiPageId(version.getWikiPageId())
                .versionNo(version.getVersionNo())
                .title(version.getTitle())
                .content(version.getContent())
                .changeNote(version.getChangeNote())
                .createdBy(version.getCreatedBy())
                .createdAt(version.getCreatedAt())
                .updatedAt(version.getUpdatedAt())
                .build();
    }

    private WikiPage getRequiredWritableDraft(Long userId, Long pageId) {
        WikiPage page = wikiPageRepository.findByIdForUpdate(pageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND));
        if (page.getDeletedAt() != null || page.getStatus() == WikiPageStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND);
        }
        resourceAccessService.requireEditWiki(userId, page.getSpaceId());
        if (page.getStatus() != WikiPageStatus.DRAFT) {
            throw new BusinessException(ErrorCode.WIKI_DRAFT_REQUIRED);
        }
        return page;
    }

    private ArtifactVersion resolveArtifactVersion(Artifact artifact, Long artifactVersionId) {
        if (artifactVersionId != null) {
            return artifactVersionRepository.findById(artifactVersionId)
                    .filter(version -> version.getArtifactId().equals(artifact.getId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.ARTIFACT_CANNOT_PUBLISH_TO_WIKI));
        }
        return artifactVersionRepository.findTopByArtifactIdOrderByVersionNoDesc(artifact.getId())
                .orElseGet(() -> {
                    ArtifactVersion version = new ArtifactVersion();
                    version.setArtifactId(artifact.getId());
                    version.setVersionNo(1);
                    version.setTaskId(artifact.getTaskId());
                    version.setTitle(artifact.getTitle());
                    version.setContent(artifact.getContent());
                    version.setChangeNote("wiki draft bootstrap");
                    version.setCreatedBy(artifact.getUserId());
                    return artifactVersionRepository.save(version);
                });
    }

    private Map<String, Object> buildWikiIndexInput(WikiPage page, WikiPageVersion version) {
        Map<String, Object> input = new HashMap<>();
        input.put("wikiPageId", page.getId());
        input.put("publishedVersionId", version.getId());
        input.put("spaceId", page.getSpaceId());
        return input;
    }

    private String buildWikiIndexIdempotencyKey(Long pageId, Long versionId) {
        return "WIKI_INDEX:" + pageId + ":" + versionId;
    }

    private void rebuildGraph(Long spaceId) {
        wikiGraphSyncService.rebuildSpaceGraph(spaceId);
    }

    private String normalizeRequired(String value) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "title/content must not be blank");
        }
        return normalized;
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
