package com.noteweave.conversation;

import com.noteweave.conversation.constraint.ConstraintPolarity;

/**
 * 一条约束行的持久化形状，与 {@code conversation_constraint_v2} 的十个字段一一对应。
 * <p>
 * 这里曾经是关键词/正则抽取器（{@code project()}、槽位词表、否定与窄化判定）。规则路径已经整体移除：
 * 抽取只走模型（{@link ConversationConstraintProjectionV2Service}），冲突判定只走
 * {@code ConstraintOpMerger}。因此本类只保留两边共同使用的行结构，
 * 不再包含任何文本解析逻辑，也不应该再被实例化。
 */
public final class ConversationConstraintProjectorV2 {

    private ConversationConstraintProjectorV2() {
    }

    /**
     * @param polarity 极性：{@code REQUIRE} 是「要求这样做」，{@code FORBID} 是「不要这样做」；
     *                 它落库到 {@code polarity} 列，回答提示词按它渲染禁止表述，不再从正文反推
     */
    public record Constraint(String id, String sourceMessageId, String kind, String scope,
                             String text, int validFromSeq, Integer invalidAfterSeq,
                             String status, String supersededBy, ConstraintPolarity polarity) {

        public Constraint {
            // 旧调用点与旧快照没有这一项：按「要求」处理，语义与加列前一致。
            polarity = polarity == null ? ConstraintPolarity.REQUIRE : polarity;
        }

        /** 缺省极性为 {@code REQUIRE} 的构造器：只关心行正文的调用点不用逐个改写。 */
        public Constraint(String id, String sourceMessageId, String kind, String scope,
                          String text, int validFromSeq, Integer invalidAfterSeq,
                          String status, String supersededBy) {
            this(id, sourceMessageId, kind, scope, text, validFromSeq, invalidAfterSeq,
                    status, supersededBy, ConstraintPolarity.REQUIRE);
        }
    }
}
