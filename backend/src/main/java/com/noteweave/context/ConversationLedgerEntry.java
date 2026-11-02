package com.noteweave.context;

import java.util.Set;

/**
 * 账本行的冻结快照。投影只读取这里的状态与正文，不读取数据库当前值。
 *
 * <p>研究在冻结之后完成时，数据库里的正文会变，但冻结快照不会。投影按快照里的 PENDING 处理，
 * 因此新完成的报告不会被写进旧冻结视图。</p>
 *
 * @param messageId        消息身份
 * @param seq              消息序号
 * @param role             角色，USER 或 ASSISTANT
 * @param answerMode       回答模式，可为空串
 * @param status           冻结时的上下文状态
 * @param text             冻结时的正文；删除消息这里必须为空串
 * @param contentSha256    冻结时的正文摘要
 * @param replyToMessageId 回复的消息身份，可为空串
 * @param researchRunId    关联的研究运行身份，可为空串
 */
public record ConversationLedgerEntry(
        String messageId,
        int seq,
        String role,
        String answerMode,
        String status,
        String text,
        String contentSha256,
        String replyToMessageId,
        String researchRunId
) {

    /** 账本状态：当前可用。 */
    public static final String STATUS_CURRENT = "CURRENT";
    /** 账本状态：助手消息等待完成。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 账本状态：失败。 */
    public static final String STATUS_FAILED = "FAILED";
    /** 账本状态：已删除，只保留位置。 */
    public static final String STATUS_DELETED = "DELETED";
    /** 账本状态：已取消。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 允许的状态取值。 */
    public static final Set<String> STATUSES = Set.of(
            STATUS_CURRENT, STATUS_PENDING, STATUS_FAILED, STATUS_DELETED, STATUS_CANCELLED);

    public ConversationLedgerEntry {
        answerMode = answerMode == null ? "" : answerMode;
        status = status == null ? "" : status;
        text = text == null ? "" : text;
        contentSha256 = contentSha256 == null ? "" : contentSha256;
        replyToMessageId = replyToMessageId == null ? "" : replyToMessageId;
        researchRunId = researchRunId == null ? "" : researchRunId;
        if (blank(messageId) || seq < 1 || blank(role)
                || !("USER".equals(role) || "ASSISTANT".equals(role))
                || !STATUSES.contains(status)) {
            throw new IllegalArgumentException("conversation ledger entry is invalid");
        }
        if (STATUS_DELETED.equals(status) && !text.isEmpty()) {
            throw new IllegalArgumentException("a deleted ledger entry cannot carry text");
        }
    }

    /** 是否是深度研究回合的助手消息。 */
    public boolean isResearch() {
        return "DEEP_RESEARCH".equals(answerMode);
    }

    /** 是否是助手消息。 */
    public boolean isAssistant() {
        return "ASSISTANT".equals(role);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
