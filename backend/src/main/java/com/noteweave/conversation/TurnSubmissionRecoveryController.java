package com.noteweave.conversation;

import com.noteweave.common.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/recovery/turn-submissions")
public class TurnSubmissionRecoveryController {

    private final ConversationTurnModule conversationTurnModule;

    public TurnSubmissionRecoveryController(ConversationTurnModule conversationTurnModule) {
        this.conversationTurnModule = conversationTurnModule;
    }

    @PostMapping("/{submissionId}/prepare")
    ApiResponse<TurnReceipt> recoverPreparation(@PathVariable String submissionId) {
        return ApiResponse.success(conversationTurnModule.recoverPreparation(submissionId));
    }
}

