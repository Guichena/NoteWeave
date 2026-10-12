package com.noteweave.chat;

import com.noteweave.answer.strategy.AnswerContext;

/** 最近对话原文只进入生成提示，不进入检索查询。 */
final class ConversationWindow {
    static final String ATTRIBUTE = "conversation_window";

    private ConversationWindow() {
    }

    static void append(StringBuilder builder, AnswerContext context) {
        if (builder == null || context == null || context.attributes() == null) {
            return;
        }
        String window = context.attributes().get(ATTRIBUTE);
        if (window == null || window.isBlank()) {
            return;
        }
        builder.append("最近对话原文（只用于理解追问，不要向用户复述）：\n");
        builder.append(window.trim()).append("\n\n");
    }
}
