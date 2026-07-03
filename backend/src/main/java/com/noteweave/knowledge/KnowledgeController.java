package com.noteweave.knowledge;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
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

    @PostMapping("/knowledge-items/{itemId}/versions")
    ApiResponse<KnowledgeItemResponse> appendVersion(
            @PathVariable String itemId,
            @Valid @RequestBody AppendKnowledgeVersionRequest request
    ) {
        return ApiResponse.success(knowledgeService.appendVersion(itemId, request));
    }

    @PostMapping("/messages/{messageId}/save-as-note")
    ApiResponse<KnowledgeItemResponse> saveAsNote(
            @PathVariable String messageId,
            @Valid @RequestBody SaveNoteRequest request
    ) {
        return ApiResponse.success(knowledgeService.saveMessageAsNote(messageId, request));
    }
}
