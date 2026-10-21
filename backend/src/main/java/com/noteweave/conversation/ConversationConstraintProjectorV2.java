package com.noteweave.conversation;

/**
 * 一条约束行的持久化形状，与 {@code conversation_constraint_v2} 的九个字段一一对应。
 * <p>
 * 这里曾经是关键词/正则抽取器（{@code project()}、槽位词表、否定与窄化判定）。规则路径已经整体移除：
 * 抽取只走模型（{@link ConversationConstraintProjectionV2Service}），冲突判定只走
 * {@code ConstraintOpMerger}。因此本类只保留两边共同使用的行结构，
 * 不再包含任何文本解析逻辑，也不应该再被实例化。
 */
public final class ConversationConstraintProjectorV2 {

    private ConversationConstraintProjectorV2() {
    }

    public record Constraint(String id, String sourceMessageId, String kind, String scope,
                             String text, int validFromSeq, Integer invalidAfterSeq,
                             String status, String supersededBy) {}
}
