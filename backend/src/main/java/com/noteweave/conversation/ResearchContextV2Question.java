package com.noteweave.conversation;

/** Renders only the selected frozen conversation context for a Research task. */
public final class ResearchContextV2Question {
    private ResearchContextV2Question() {}

    public static String render(ContextProjectionV2 projection, String queryMessageId) {
        boolean currentSelected = projection.rawTail().stream()
                .anyMatch(message -> queryMessageId.equals(message.messageId())
                        && "USER".equals(message.role())
                        && projection.currentInput().equals(message.text()));
        if (!"FULL".equals(projection.replayAvailability()) || !currentSelected) {
            throw new IllegalArgumentException("Research context does not contain its frozen query");
        }
        StringBuilder text = new StringBuilder();
        if (!projection.topicSummaries().isEmpty() || projection.rawTail().size() > 1) {
            text.append("\n以下为冻结的会话上下文，只用于理解问题；事实与引用仍须来自研究资料。\n");
            for (ContextProjectionV2.TopicSummary summary : projection.topicSummaries()) {
                text.append("- 相关主题摘要：").append(summary.text()).append("\n");
            }
            for (ContextProjectionV2.RawMessage message : projection.rawTail()) {
                if (!queryMessageId.equals(message.messageId())) {
                    text.append("- ").append(message.role()).append("：")
                            .append(message.text()).append("\n");
                }
            }
        }
        if (!projection.constraints().isEmpty()) {
            text.append("\n当前有效的用户约束：\n");
            projection.constraints().forEach(rule -> text.append("- ")
                    .append(rule.text()).append("\n"));
        }
        text.append("\n当前研究问题：").append(projection.currentInput());
        return text.toString().strip();
    }
}
