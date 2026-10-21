package com.noteweave.conversation.constraint;

import java.util.List;

/**
 * 把一条用户消息转成约束操作（模型优先路径）。
 * <p>
 * 实现只负责「拿到 ops 或明确失败」，不负责合并、不写库：冲突判定统一由 {@code ConstraintOpMerger} 做。
 * 失败必须显式表达（{@link Result.Unusable}），不允许用空 ops 冒充「模型认为没有约束」——
 * 前者会回落到规则路径，后者是终局结论。
 */
public interface ConstraintOpsExtractor {

    /**
     * 抽取一条消息里的约束操作。
     *
     * @param request 消息与当前生效的约束
     * @return {@link Result.Ok}（ops 可能为空数组）或 {@link Result.Unusable}
     */
    Result extract(Request request);

    /**
     * 一次抽取的输入。
     *
     * @param messageId 源消息 id，用于生成稳定的约束 id
     * @param seq       消息在会话里的序号
     * @param message   消息正文（只作为待分析的数据进提示词）
     * @param topicId   当前话题 id，用于 CURRENT_TOPIC 作用范围的隔离
     * @param active    当前 ACTIVE 约束，模型只能引用其中的 id 作为 targetId
     */
    record Request(
            String messageId,
            int seq,
            String message,
            String topicId,
            List<ActiveConstraint> active
    ) {

        public Request {
            active = active == null ? List.of() : List.copyOf(active);
        }
    }

    /**
     * 提示词里可见的一条已生效约束。
     * <p>
     * {@code scope} 保持字符串：它来自 {@code conversation_constraint_v2.scope}，
     * 只有 {@code CURRENT_TOPIC}/{@code CONVERSATION} 两个落库值，不需要再经过枚举归一。
     */
    record ActiveConstraint(
            String id,
            ConstraintSlot slot,
            String value,
            String scope,
            String text,
            int validFromSeq
    ) {
    }

    /** 抽取结果。 */
    sealed interface Result {

        /**
         * 模型给出了合法 ops。
         *
         * @param ops           操作列表，空列表表示模型明确认为没有约束
         * @param promptVersion 提示词版本，写进 {@code rule_version} 以便区分模型/规则路径
         */
        record Ok(List<ConstraintOp> ops, String promptVersion) implements Result {

            public Ok {
                ops = ops == null ? List.of() : List.copyOf(ops);
                promptVersion = promptVersion == null ? "" : promptVersion;
            }
        }

        /**
         * 模型不可用 / 超时 / 调用失败 / 输出不合规。
         *
         * @param reason 稳定的原因常量，只用于日志与降级判断
         */
        record Unusable(String reason) implements Result {
        }
    }
}
