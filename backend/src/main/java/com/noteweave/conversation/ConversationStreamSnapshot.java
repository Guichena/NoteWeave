package com.noteweave.conversation;

import com.noteweave.answer.AnswerRunResponse;
import java.util.List;

public record ConversationStreamSnapshot(
        String conversationId,
        List<AnswerRunResponse> answerRuns
) {
}
