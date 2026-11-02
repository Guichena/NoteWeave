package com.noteweave.conversation;

import com.noteweave.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    @PostMapping("/session")
    ApiResponse<ConversationSessionPreferenceService.SessionRequirement> sessionOnly(
            @PathVariable String workspaceId,
            @PathVariable String conversationId,
            @Valid @RequestBody SessionPreferenceRequest request
    ) {
        return ApiResponse.success(service.sessionOnly(
                workspaceId, conversationId, request.revisionId()));
    }

    public record SessionPreferenceRequest(@NotBlank String revisionId) {
    }
}
