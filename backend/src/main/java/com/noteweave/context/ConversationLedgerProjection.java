package com.noteweave.context;

import java.util.List;
import java.util.Set;

/**
 * 账本投影结果：按状态与类型选择可用形态，保留序号与截止一致性。
 *
 * <p>删除、失败、取消与进行中的消息都保留账本位置，只是不提供正文。研究 PENDING 消息可以在
 * 任何历史位置形成冻结句柄，不假定只有末尾一条占位。序号必须连续，出现空洞时记录原因并标记
 * 不连续，不能悄悄把前后两条接在一起。</p>
 */
public record ConversationLedgerProjection(
        String schemaVersion,
        String conversationId,
        int cutoffSeq,
        List<Projected> entries,
        boolean continuous,
        List<String> reasons
) {

    /** 投影结构版本。 */
    public static final String SCHEMA_VERSION = "conversation-ledger-projection-v1";

    public ConversationLedgerProjection {
        entries = entries == null ? List.of() : List.copyOf(entries);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        conversationId = conversationId == null ? "" : conversationId;
        if (!SCHEMA_VERSION.equals(schemaVersion) || cutoffSeq < 0) {
            throw new IllegalArgumentException("ledger projection envelope is invalid");
        }
        int previous = 0;
        Set<String> ids = new java.util.HashSet<>();
        for (Projected entry : entries) {
            if (entry.seq() <= previous || entry.seq() > cutoffSeq || !ids.add(entry.messageId())) {
                throw new IllegalArgumentException("ledger projection entries must be ordered within cutoff");
            }
            previous = entry.seq();
        }
        for (String reason : reasons) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("ledger projection reasons must be explicit");
            }
        }
    }

    /** 正文可用的原文条目。 */
    public List<Projected> rawEntries() {
        return entries.stream().filter(entry -> entry.form() == ContextItemForm.RAW).toList();
    }

    /** 可回忆句柄条目。 */
    public List<Projected> handles() {
        return entries.stream().filter(entry -> entry.form() == ContextItemForm.HANDLE).toList();
    }

    /** 占位条目，正文不可用。 */
    public List<Projected> placeholders() {
        return entries.stream().filter(entry -> entry.form() == ContextItemForm.PLACEHOLDER).toList();
    }

    /** 按身份取条目，不存在返回 null。 */
    public Projected byMessageId(String messageId) {
        return entries.stream()
                .filter(entry -> entry.messageId().equals(messageId))
                .findFirst().orElse(null);
    }

    /**
     * 投影后的账本条目。
     *
     * @param messageId    消息身份
     * @param seq          序号
     * @param role         角色
     * @param status       冻结时的账本状态
     * @param form         可用形态
     * @param refType      引用类型
     * @param refId        引用身份
     * @param text         可用正文，占位与句柄为空串
     * @param startSeq     覆盖起始序号
     * @param endSeq       覆盖结束序号
     * @param reason       稳定原因码
     * @param expandable   是否可以经回忆展开
     * @param researchRunId 关联研究运行，可为空串
     */
    public record Projected(
            String messageId,
            int seq,
            String role,
            String status,
            ContextItemForm form,
            ContextRefType refType,
            String refId,
            String text,
            int startSeq,
            int endSeq,
            String reason,
            boolean expandable,
            String researchRunId
    ) {
        public Projected {
            text = text == null ? "" : text;
            researchRunId = researchRunId == null ? "" : researchRunId;
            if (messageId == null || messageId.isBlank() || seq < 1 || role == null
                    || status == null || form == null || refType == null || refId == null
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("projected ledger entry is invalid");
            }
            if (form != ContextItemForm.RAW && !text.isEmpty()) {
                throw new IllegalArgumentException("only raw entries may carry text");
            }
        }
    }
}
