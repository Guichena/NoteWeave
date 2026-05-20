package com.noteweave.team.wiki.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.team.wiki.dto.CreateWikiDraftFromMessageRequest;
import com.noteweave.team.wiki.dto.CreateWikiDraftRequest;
import com.noteweave.team.wiki.dto.PublishArtifactToWikiRequest;
import com.noteweave.team.wiki.dto.WikiGraphResponse;
import com.noteweave.team.wiki.dto.PublishWikiPageRequest;
import com.noteweave.team.wiki.dto.UpdateWikiPageRequest;
import com.noteweave.team.wiki.dto.WikiPageResponse;
import com.noteweave.team.wiki.dto.WikiPageVersionResponse;
import com.noteweave.team.wiki.dto.WikiSearchResponse;
import com.noteweave.team.wiki.service.TeamWikiService;
import com.noteweave.team.wiki.service.WikiGraphService;
import com.noteweave.team.wiki.service.WikiSearchService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TeamWikiController {

    private final TeamWikiService teamWikiService;
    private final WikiSearchService wikiSearchService;
    private final WikiGraphService wikiGraphService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/team/spaces/{spaceId}/wiki-pages")
    public ApiResponse<WikiPageResponse> createDraft(
            @PathVariable Long spaceId,
            @Valid @RequestBody CreateWikiDraftRequest request
    ) {
        return ApiResponse.success(teamWikiService.createDraft(currentUserProvider.getCurrentUserId(), spaceId, request));
    }

    @GetMapping("/team/spaces/{spaceId}/wiki-pages")
    public ApiResponse<List<WikiPageResponse>> list(@PathVariable Long spaceId) {
        return ApiResponse.success(teamWikiService.list(currentUserProvider.getCurrentUserId(), spaceId));
    }

    @GetMapping("/team/wiki-pages/{pageId}")
    public ApiResponse<WikiPageResponse> get(@PathVariable Long pageId) {
        return ApiResponse.success(teamWikiService.get(currentUserProvider.getCurrentUserId(), pageId));
    }

    @PutMapping("/team/wiki-pages/{pageId}")
    public ApiResponse<WikiPageResponse> updateDraft(
            @PathVariable Long pageId,
            @Valid @RequestBody UpdateWikiPageRequest request
    ) {
        return ApiResponse.success(teamWikiService.updateDraft(currentUserProvider.getCurrentUserId(), pageId, request));
    }

    @PostMapping("/team/wiki-pages/{pageId}/publish")
    public ApiResponse<WikiPageResponse> publish(
            @PathVariable Long pageId,
            @RequestBody(required = false) PublishWikiPageRequest request
    ) {
        return ApiResponse.success(teamWikiService.publish(currentUserProvider.getCurrentUserId(), pageId, request));
    }

    @DeleteMapping("/team/wiki-pages/{pageId}")
    public ApiResponse<Void> archive(@PathVariable Long pageId) {
        teamWikiService.archive(currentUserProvider.getCurrentUserId(), pageId);
        return ApiResponse.success(null);
    }

    @GetMapping("/team/wiki-pages/{pageId}/versions")
    public ApiResponse<List<WikiPageVersionResponse>> versions(@PathVariable Long pageId) {
        return ApiResponse.success(teamWikiService.listVersions(currentUserProvider.getCurrentUserId(), pageId));
    }

    @PostMapping("/team/chat-messages/{messageId}/wiki-drafts")
    public ApiResponse<WikiPageResponse> createDraftFromMessage(
            @PathVariable Long messageId,
            @Valid @RequestBody CreateWikiDraftFromMessageRequest request
    ) {
        return ApiResponse.success(teamWikiService.createDraftFromMessage(currentUserProvider.getCurrentUserId(), messageId, request));
    }

    @PostMapping("/artifacts/{artifactId}/publish-to-wiki")
    public ApiResponse<WikiPageResponse> createDraftFromArtifact(
            @PathVariable Long artifactId,
            @Valid @RequestBody PublishArtifactToWikiRequest request
    ) {
        return ApiResponse.success(teamWikiService.createDraftFromArtifact(currentUserProvider.getCurrentUserId(), artifactId, request));
    }

    @GetMapping("/team/spaces/{spaceId}/wiki-pages/search")
    public ApiResponse<WikiSearchResponse> search(
            @PathVariable Long spaceId,
            @RequestParam String keyword
    ) {
        return ApiResponse.success(wikiSearchService.search(currentUserProvider.getCurrentUserId(), spaceId, keyword));
    }

    @GetMapping("/team/spaces/{spaceId}/wiki-graph")
    public ApiResponse<WikiGraphResponse> graph(@PathVariable Long spaceId) {
        return ApiResponse.success(wikiGraphService.getSpaceGraph(currentUserProvider.getCurrentUserId(), spaceId));
    }

    @GetMapping("/team/wiki-pages/{pageId}/graph")
    public ApiResponse<WikiGraphResponse> pageGraph(
            @PathVariable Long pageId,
            @RequestParam(defaultValue = "1") int depth
    ) {
        return ApiResponse.success(wikiGraphService.getPageSubgraph(currentUserProvider.getCurrentUserId(), pageId, depth));
    }
}
