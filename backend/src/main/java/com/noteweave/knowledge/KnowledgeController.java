package com.noteweave.knowledge;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final WikiIngestService wikiIngestService;

    public KnowledgeController(KnowledgeService knowledgeService, WikiIngestService wikiIngestService) {
        this.knowledgeService = knowledgeService;
        this.wikiIngestService = wikiIngestService;
    }

    @PostMapping("/workspaces/{workspaceId}/knowledge-items")
    ApiResponse<KnowledgeItemResponse> createItem(
            @PathVariable String workspaceId,
            @Valid @RequestBody KnowledgeItemRequest request
    ) {
        return ApiResponse.success(knowledgeService.createItem(workspaceId, request));
    }

    @GetMapping("/workspaces/{workspaceId}/knowledge-items")
    ApiResponse<List<KnowledgeItemResponse>> listItems(
            @PathVariable String workspaceId,
            @RequestParam(name = "item_type", defaultValue = "WIKI") String itemType
    ) {
        return ApiResponse.success(knowledgeService.listItems(workspaceId, itemType));
    }

    @GetMapping("/workspaces/{workspaceId}/wiki-home")
    ApiResponse<WikiHomeResponse> wikiHome(@PathVariable String workspaceId) {
        return ApiResponse.success(knowledgeService.getWikiHome(workspaceId));
    }

    @GetMapping("/workspaces/{workspaceId}/wiki-index")
    ApiResponse<WikiIndexResponse> wikiIndex(@PathVariable String workspaceId) {
        return ApiResponse.success(knowledgeService.getWikiIndex(workspaceId));
    }

    @GetMapping("/workspaces/{workspaceId}/wiki-search")
    ApiResponse<List<KnowledgeItemResponse>> searchWiki(
            @PathVariable String workspaceId,
            @RequestParam(name = "q", defaultValue = "") String query
    ) {
        return ApiResponse.success(knowledgeService.searchWikiPages(workspaceId, query));
    }

    @GetMapping("/workspaces/{workspaceId}/wiki-graph")
    ApiResponse<WikiGraphResponse> wikiGraph(
            @PathVariable String workspaceId,
            @RequestParam(name = "mode", defaultValue = "overview") String mode,
            @RequestParam(name = "center", defaultValue = "") String centerItemId,
            @RequestParam(name = "depth", defaultValue = "1") int depth,
            @RequestParam(name = "limit", defaultValue = "24") int limit,
            @RequestParam(name = "kinds", required = false) List<String> pageKinds
    ) {
        return ApiResponse.success(knowledgeService.getWikiGraph(workspaceId, mode, centerItemId, depth, limit, pageKinds));
    }

    @GetMapping("/workspaces/{workspaceId}/wiki-stats")
    ApiResponse<WikiStatsResponse> wikiStats(@PathVariable String workspaceId) {
        return ApiResponse.success(knowledgeService.getWikiStats(workspaceId));
    }

    @GetMapping("/workspaces/{workspaceId}/wiki/rebuild-advice")
    ApiResponse<WikiRebuildAdviceResponse> wikiRebuildAdvice(@PathVariable String workspaceId) {
        return ApiResponse.success(knowledgeService.getWikiRebuildAdvice(workspaceId));
    }

    @GetMapping("/workspaces/{workspaceId}/wiki-issues")
    ApiResponse<List<WikiIssueResponse>> wikiIssues(
            @PathVariable String workspaceId,
            @RequestParam(name = "issue_type", required = false) String issueType,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "auto_fixable", required = false) Boolean autoFixable,
            @RequestParam(name = "item_id", required = false) String itemId
    ) {
        return ApiResponse.success(knowledgeService.listWikiIssues(workspaceId, issueType, severity, autoFixable, itemId));
    }

    @GetMapping("/workspaces/{workspaceId}/wiki-log")
    ApiResponse<List<WikiLogEntryResponse>> wikiLog(
            @PathVariable String workspaceId,
            @RequestParam(name = "item_id", required = false) String itemId
    ) {
        return ApiResponse.success(knowledgeService.listWikiLog(workspaceId, itemId));
    }

    @PostMapping("/workspaces/{workspaceId}/wiki/rebuild-links")
    ApiResponse<WikiStatsResponse> rebuildWikiLinks(@PathVariable String workspaceId) {
        return ApiResponse.success(knowledgeService.rebuildWikiLinks(workspaceId));
    }

    @PostMapping("/workspaces/{workspaceId}/wiki/rebuild")
    ApiResponse<WikiRebuildResponse> rebuildWiki(@PathVariable String workspaceId) {
        return ApiResponse.success(wikiIngestService.enqueueAndRunWorkspaceIngestIfEnabled(workspaceId, "manual_rebuild"));
    }

    @PostMapping("/workspaces/{workspaceId}/wiki/auto-fix")
    ApiResponse<WikiAutoFixResponse> autoFixWiki(@PathVariable String workspaceId) {
        return ApiResponse.success(knowledgeService.autoFixWiki(workspaceId));
    }

    @GetMapping("/knowledge-items/{itemId}")
    ApiResponse<KnowledgeItemDetailResponse> getItem(@PathVariable String itemId) {
        return ApiResponse.success(knowledgeService.getItemDetail(itemId));
    }

    @GetMapping("/knowledge-items/{itemId}/versions")
    ApiResponse<List<KnowledgeVersionSummaryResponse>> listVersions(@PathVariable String itemId) {
        return ApiResponse.success(knowledgeService.listItemVersions(itemId));
    }

    @GetMapping("/knowledge-items/{itemId}/versions/{versionNo}")
    ApiResponse<KnowledgeVersionDetailResponse> getVersion(
            @PathVariable String itemId,
            @PathVariable int versionNo
    ) {
        return ApiResponse.success(knowledgeService.getItemVersionDetail(itemId, versionNo));
    }

    @PostMapping("/knowledge-items/{itemId}/versions")
    ApiResponse<KnowledgeItemResponse> appendVersion(
            @PathVariable String itemId,
            @Valid @RequestBody AppendKnowledgeVersionRequest request
    ) {
        return ApiResponse.success(knowledgeService.appendVersion(itemId, request));
    }

    @PatchMapping("/knowledge-items/{itemId}/title")
    ApiResponse<KnowledgeItemResponse> renameItem(
            @PathVariable String itemId,
            @Valid @RequestBody RenameKnowledgeItemRequest request
    ) {
        return ApiResponse.success(knowledgeService.renameItem(itemId, request));
    }

    @DeleteMapping("/knowledge-items/{itemId}")
    ApiResponse<Void> deleteItem(@PathVariable String itemId) {
        knowledgeService.deleteItem(itemId);
        return ApiResponse.success(null);
    }

    @PostMapping("/messages/{messageId}/save-as-note")
    ApiResponse<KnowledgeItemResponse> saveAsNote(
            @PathVariable String messageId,
            @Valid @RequestBody SaveNoteRequest request
    ) {
        return ApiResponse.success(knowledgeService.saveMessageAsNote(messageId, request));
    }
}
