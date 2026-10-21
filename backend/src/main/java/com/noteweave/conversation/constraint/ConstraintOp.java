package com.noteweave.conversation.constraint;

import java.util.Objects;

/**
 * 模型对一条用户消息给出的一条约束操作。
 * <p>
 * 这里只做「结构与长度」校验（非空、取值长度），语义校验（ADD/UPDATE 必须有 slot、REVOKE 必须有目标）
 * 在 {@link ConstraintOpSchema} 里完成，冲突合并留给 {@code ConstraintOpMerger}。
 *
 * @param op         操作类型，不能为空
 * @param slot       目标槽位；{@code NOOP} 与无目标的 {@code REVOKE} 可以为 {@code null}
 * @param value      槽位取值，如 {@code 中文}、{@code format.table}；可为 {@code null}
 * @param scope      作用范围；{@code null} 表示模型没有声明，由合并器按「不降级」处理
 * @param targetId   被撤回/被更新的已有约束 id；可为 {@code null}
 * @param confidence 置信度，必须在 {@code [0,1]}
 */
public record ConstraintOp(
        Op op,
        ConstraintSlot slot,
        String value,
        ConstraintScope scope,
        String targetId,
        double confidence
) {

    /** 模型没给 confidence 时的缺省值：高于默认门槛，又不至于把猜测当确定。 */
    public static final double DEFAULT_CONFIDENCE = 0.7;
    /** 取值长度上限：与 {@code constraint_text longtext} 的写入习惯一致，防止提示词注入塞长文本。 */
    public static final int MAX_VALUE_CHARS = 200;

    public ConstraintOp {
        op = Objects.requireNonNull(op, "op");
        value = blankToNull(value);
        if (value != null && value.codePointCount(0, value.length()) > MAX_VALUE_CHARS) {
            throw new IllegalArgumentException("value 超过 " + MAX_VALUE_CHARS + " 字符");
        }
        targetId = blankToNull(targetId);
        if (!(confidence >= 0.0 && confidence <= 1.0)) {
            throw new IllegalArgumentException("confidence 必须在 0 和 1 之间");
        }
    }

    /** 该操作是否必须带槽位。 */
    public boolean requiresSlot() {
        return op == Op.ADD || op == Op.UPDATE;
    }

    /** 该操作是否必须能定位到已有约束（id 或槽位）。 */
    public boolean requiresTarget() {
        return op == Op.REVOKE;
    }

    /** 该操作是否新增/改写一行约束。 */
    public boolean writesRow() {
        return op == Op.ADD || op == Op.UPDATE;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 操作类型。 */
    public enum Op {
        ADD, UPDATE, REVOKE, NOOP
    }
}
