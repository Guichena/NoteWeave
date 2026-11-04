package com.noteweave.conversation;

import java.util.List;

/**
 * 会话删除结果：显式给出 deleted 标记，调用方不必再根据状态词猜测。
 * cancelledAnswerRunIds 是删除时一并停止的进行中回答。
 */
public record DeletedConversationResponse(
        String conversationId,
        String status,
        boolean deleted,
        List<String> cancelledAnswerRunIds
) {
}
