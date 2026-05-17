package com.noteweave.team.wiki.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.task.worker.TaskExecutionContext;
import com.noteweave.task.worker.TaskResult;
import com.noteweave.team.wiki.model.WikiIndexStatus;
import com.noteweave.team.wiki.model.WikiPage;
import com.noteweave.team.wiki.model.WikiPageStatus;
import com.noteweave.team.wiki.model.WikiPageVersion;
import com.noteweave.team.wiki.repository.WikiPageRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WikiIndexService {

    private final WikiPageRepository wikiPageRepository;
    private final TeamWikiService teamWikiService;
    private final SearchIndexWikiSupport searchIndexWikiSupport;

    @Transactional
    public TaskResult executeIndexTask(TaskExecutionContext context) {
        WikiIndexTaskInput input = context.readInput(WikiIndexTaskInput.class);
        context.publishProgress("wiki index started", Map.of("wikiPageId", input.getWikiPageId()));

        WikiPage page = wikiPageRepository.findByIdForUpdate(input.getWikiPageId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WIKI_PAGE_NOT_FOUND));
        if (page.getDeletedAt() != null || page.getStatus() == WikiPageStatus.ARCHIVED) {
            teamWikiService.markIndexFailed(page.getId());
            throw new BusinessException(ErrorCode.WIKI_INDEX_FAILED, "wiki page no longer available");
        }
        if (page.getStatus() != WikiPageStatus.PUBLISHED || !input.getPublishedVersionId().equals(page.getPublishedVersionId())) {
            teamWikiService.markIndexFailed(page.getId());
            throw new BusinessException(ErrorCode.WIKI_INDEX_FAILED, "wiki page version is stale");
        }

        WikiPageVersion version = teamWikiService.getRequiredPublishedVersion(page.getId(), input.getPublishedVersionId());
        searchIndexWikiSupport.index(new SearchIndexWikiSupport.WikiIndexDocument(
                buildEsDocId(page.getId(), version.getId()),
                page.getSpaceId(),
                page.getId(),
                version.getId(),
                version.getTitle(),
                version.getContent(),
                page.getStatus().name(),
                WikiIndexStatus.INDEXED.name(),
                page.getSourceArtifactId(),
                page.getUpdatedAt() == null ? java.time.Instant.now().toString() : page.getUpdatedAt().toString()
        ));
        teamWikiService.markIndexed(page.getId());
        context.publishProgress("wiki index completed", Map.of("wikiPageId", input.getWikiPageId()));
        return TaskResult.builder()
                .output(Map.of("wikiPageId", page.getId(), "publishedVersionId", version.getId()))
                .resultRefType("WIKI_PAGE_VERSION")
                .resultRefId(version.getId())
                .build();
    }

    public String buildEsDocId(Long wikiPageId, Long publishedVersionId) {
        return "wiki:" + wikiPageId + ":" + publishedVersionId;
    }

    public void remove(Long wikiPageId) {
        searchIndexWikiSupport.deleteByWikiPageId(wikiPageId);
    }
}
