package com.noteweave.conversation;

import com.noteweave.common.ApiResponse;
import com.noteweave.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** F5-03：对话内确认“只用于本对话”。 */
@RestController
@RequestMapping("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/preferences")
public class ConversationSessionPreferenceController {

    private final ConversationSessionPreferenceService service;

    public ConversationSessionPreferenceController(ConversationSessionPreferenceService service) {
        this.service = service;
    }

    @GetMapping("/session")
    ApiResponse<List<ConversationSessionPreferenceService.SessionRequirement>> list(
            @PathVariable String workspaceId,
            @PathVariable String conversationId
    ) {
        return ApiResponse.success(service.list(workspaceId, conversationId));
    }

    @GetMapping("/active")
    ApiResponse<List<ConversationSessionPreferenceService.PendingProposal>> activeMemories(
            @PathVariable String workspaceId,
            @PathVariable String conversationId
    ) {
        return ApiResponse.success(service.activeMemories(workspaceId, conversationId));
    }

    @GetMapping("/proposals")
    ApiResponse<List<ConversationSessionPreferenceService.PendingProposal>> pendingProposals(
            @PathVariable String workspaceId,
            @PathVariable String conversationId
    ) {
        return ApiResponse.success(service.pendingProposals(workspaceId, conversationId));
    }

    @PostMapping("/session")
    ApiResponse<ConversationSessionPreferenceService.SessionRequirement> sessionOnly(
            @PathVariable String workspaceId,
            @PathVariable String conversationId,
            @Valid @RequestBody SessionPreferenceRequest request
    ) {
        boolean hasRevision = request.revisionId() != null && !request.revisionId().isBlank();
        boolean hasText = request.text() != null && !request.text().isBlank();
        if (hasRevision == hasText) {
            throw new BusinessException("SESSION_REQUIREMENT_INPUT_INVALID",
                    "会话要求需要 revision_id 或 text 之一", HttpStatus.BAD_REQUEST);
        }
        return ApiResponse.success(hasRevision
                ? service.sessionOnly(workspaceId, conversationId, request.revisionId())
                : service.addText(workspaceId, conversationId, request.text()));
    }

    @DeleteMapping("/session/{requirementId}")
    ApiResponse<Void> deactivate(
            @PathVariable String workspaceId,
            @PathVariable String conversationId,
            @PathVariable String requirementId
    ) {
        service.deactivate(workspaceId, conversationId, requirementId);
        return ApiResponse.success(null);
    }

    public record SessionPreferenceRequest(
            @Size(max = 36) String revisionId,
            @Size(max = 500) String text
    ) {
    }
}
