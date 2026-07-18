package com.noteweave.conversation;

import com.noteweave.chat.SendMessageRequest;
import java.util.List;

public record SubmitTurnCommand(
        String conversationId,
        String content,
        String requestedTurnMode,
        String clientRequestId,
        List<String> sourceScope,
        String expectedHistoryHeadMessageId,
        String retrievalStrategy,
        List<String> retrievalChannels,
        List<String> groundingRefs
) {
    public SubmitTurnCommand {
        sourceScope = sourceScope == null ? List.of() : List.copyOf(sourceScope);
        retrievalChannels = retrievalChannels == null ? List.of() : List.copyOf(retrievalChannels);
        groundingRefs = groundingRefs == null ? List.of() : List.copyOf(groundingRefs);
    }

    public static SubmitTurnCommand from(String conversationId, SendMessageRequest request) {
        return new SubmitTurnCommand(
                conversationId,
                request.content(),
                request.answerMode(),
                request.clientRequestId(),
                request.sourceScopeSourceIds(),
                request.expectedHistoryHeadMessageId(),
                request.retrievalStrategy(),
                request.retrievalChannels(),
                request.groundingRefs()
        );
    }

    public SubmitTurnCommand(
            String conversationId,
            String content,
            String requestedTurnMode,
            String clientRequestId,
            List<String> sourceScope,
            String expectedHistoryHeadMessageId
    ) {
        this(conversationId, content, requestedTurnMode, clientRequestId, sourceScope,
                expectedHistoryHeadMessageId, null, List.of(), List.of());
    }

    public EffectiveRetrievalConfig effectiveRetrievalConfig() {
        return EffectiveRetrievalConfig.resolve(retrievalStrategy, retrievalChannels, sourceScope, groundingRefs);
    }

    SendMessageRequest toLegacyAnswerRequest() {
        return new SendMessageRequest(content, requestedTurnMode, clientRequestId, sourceScope, null,
                retrievalStrategy, retrievalChannels, groundingRefs);
    }
}
