package com.noteweave.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 账本投影器：把冻结的账本行按状态与类型投影成可用形态。
 *
 * <p>规则来自架构设计第 7.4 节：删除消息保留必要账本位置但不提供正文；研究 PENDING 消息在任何
 * 历史位置都可以形成冻结句柄；失败、取消和普通回答 PENDING 单独定义。投影只读取冻结快照，
 * 不读取数据库当前值，因此研究在冻结之后完成不会把新报告写进旧视图。</p>
 */
public final class ConversationLedgerProjector {

    /** 投影实现版本，改动规则必须递增。 */
    public static final String PROJECTOR_VERSION = "conversation-ledger-v1";

    /** 原文可用。 */
    public static final String REASON_RAW_AVAILABLE = "RAW_AVAILABLE";
    /** 当前状态但正文为空。 */
    public static final String REASON_CURRENT_WITHOUT_TEXT = "CURRENT_WITHOUT_TEXT";
    /** 研究进行中，形成冻结句柄。 */
    public static final String REASON_RESEARCH_PENDING_HANDLE = "RESEARCH_PENDING_HANDLE";
    /** 研究进行中且冻结后写入了正文：仍按句柄处理，不把新报告写进旧快照。 */
    public static final String REASON_RESEARCH_PENDING_IGNORES_LATE_TEXT =
            "RESEARCH_PENDING_IGNORES_LATE_TEXT";
    /** 普通回答等待完成。 */
    public static final String REASON_ANSWER_PENDING_PLACEHOLDER = "ANSWER_PENDING_PLACEHOLDER";
    /** 已删除，只保留账本位置。 */
    public static final String REASON_DELETED_LEDGER_POSITION_ONLY = "DELETED_LEDGER_POSITION_ONLY";
    /** 失败占位。 */
    public static final String REASON_FAILED_PLACEHOLDER = "FAILED_PLACEHOLDER";
    /** 取消占位。 */
    public static final String REASON_CANCELLED_PLACEHOLDER = "CANCELLED_PLACEHOLDER";
    /** 序号空洞。 */
    public static final String REASON_LEDGER_GAP_PREFIX = "LEDGER_GAP_AT_";

    /** 投影一份冻结账本。 */
    public ConversationLedgerProjection project(String conversationId, int cutoffSeq,
                                                List<ConversationLedgerEntry> entries) {
        if (cutoffSeq < 0 || entries == null) {
            throw new IllegalArgumentException("ledger projection requires a cutoff and frozen entries");
        }
        List<ConversationLedgerEntry> ordered = new ArrayList<>();
        for (ConversationLedgerEntry entry : entries) {
            if (entry.seq() <= cutoffSeq) {
                ordered.add(entry);
            }
        }
        ordered.sort(Comparator.comparingInt(ConversationLedgerEntry::seq));
        List<String> reasons = new ArrayList<>();
        int expected = 1;
        for (ConversationLedgerEntry entry : ordered) {
            while (expected < entry.seq()) {
                reasons.add(REASON_LEDGER_GAP_PREFIX + expected);
                expected++;
            }
            if (entry.seq() != expected) {
                throw new IllegalArgumentException("duplicate ledger sequence " + entry.seq());
            }
            expected++;
        }
        while (expected <= cutoffSeq) {
            reasons.add(REASON_LEDGER_GAP_PREFIX + expected);
            expected++;
        }
        List<ConversationLedgerProjection.Projected> projected = new ArrayList<>();
        for (ConversationLedgerEntry entry : ordered) {
            projected.add(project(entry));
        }
        boolean continuous = reasons.isEmpty();
        return new ConversationLedgerProjection(ConversationLedgerProjection.SCHEMA_VERSION,
                conversationId == null ? "" : conversationId, cutoffSeq, projected, continuous, reasons);
    }

    /** 单条账本行的投影。 */
    public ConversationLedgerProjection.Projected project(ConversationLedgerEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("ledger entry is required");
        }
        return switch (entry.status()) {
            case ConversationLedgerEntry.STATUS_CURRENT -> entry.text().isBlank()
                    ? placeholder(entry, REASON_CURRENT_WITHOUT_TEXT)
                    : new ConversationLedgerProjection.Projected(entry.messageId(), entry.seq(),
                    entry.role(), entry.status(), ContextItemForm.RAW, ContextRefType.MESSAGE,
                    entry.messageId(), entry.text(), entry.seq(), entry.seq(),
                    REASON_RAW_AVAILABLE, false, entry.researchRunId());
            case ConversationLedgerEntry.STATUS_PENDING -> entry.isAssistant() && entry.isResearch()
                    ? new ConversationLedgerProjection.Projected(entry.messageId(), entry.seq(),
                    entry.role(), entry.status(), ContextItemForm.HANDLE, ContextRefType.MESSAGE,
                    entry.messageId(), "", entry.seq(), entry.seq(),
                    entry.text().isBlank() ? REASON_RESEARCH_PENDING_HANDLE
                            : REASON_RESEARCH_PENDING_IGNORES_LATE_TEXT,
                    true, entry.researchRunId())
                    : placeholder(entry, REASON_ANSWER_PENDING_PLACEHOLDER);
            case ConversationLedgerEntry.STATUS_DELETED -> placeholder(entry,
                    REASON_DELETED_LEDGER_POSITION_ONLY);
            case ConversationLedgerEntry.STATUS_FAILED -> placeholder(entry, REASON_FAILED_PLACEHOLDER);
            case ConversationLedgerEntry.STATUS_CANCELLED -> placeholder(entry,
                    REASON_CANCELLED_PLACEHOLDER);
            default -> throw new IllegalArgumentException("unsupported ledger status " + entry.status());
        };
    }

    private ConversationLedgerProjection.Projected placeholder(ConversationLedgerEntry entry, String reason) {
        return new ConversationLedgerProjection.Projected(entry.messageId(), entry.seq(), entry.role(),
                entry.status(), ContextItemForm.PLACEHOLDER, ContextRefType.LEDGER_PLACEHOLDER,
                entry.messageId(), "", entry.seq(), entry.seq(), reason, false, entry.researchRunId());
    }
}
