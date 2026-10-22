package com.noteweave.conversation.constraint;

import java.util.Locale;

/**
 * 约束的极性：同一槽位既可以是「要求这样做」，也可以是「不要这样做」。
 * <p>
 * 没有这个字段时，「不要用表格」只能落成 {@code slot=table} 的 ADD，合并器与回答提示词都把它当成
 * 正向表格要求。带目标的否定现在用 {@link #FORBID} + 被否定对象的槽位表达；
 * 只有没有目标的否定（"不要再贴大段配置"）才用 {@link ConstraintSlot#NEGATION}。
 */
public enum ConstraintPolarity {

    /** 要求采用该槽位（缺省值）。 */
    REQUIRE("require"),
    /** 要求避免该槽位；回答提示词里渲染成「不要使用表格」这类禁止项。 */
    FORBID("forbid");

    private final String wire;

    ConstraintPolarity(String wire) {
        this.wire = wire;
    }

    /** 提示词与模型输出里的字面量。 */
    public String wire() {
        return wire;
    }

    /** 落库取值：{@code conversation_constraint_v2.polarity} 与 {@code status} 列一样用大写枚举名。 */
    public String dbValue() {
        return name();
    }

    /**
     * 读回库里的极性。
     * <p>
     * {@code null}/空白按 {@link #REQUIRE} 处理，与 {@link ConstraintOp} 兼容旧基线的口径一致；
     * 其余不认识的值说明有人绕过校验写了库，直接抛错而不是猜一个极性。
     */
    public static ConstraintPolarity fromDb(String value) {
        if (value == null || value.isBlank()) {
            return REQUIRE;
        }
        for (ConstraintPolarity polarity : values()) {
            if (polarity.name().equalsIgnoreCase(value.trim())) {
                return polarity;
            }
        }
        throw new IllegalArgumentException("unknown constraint polarity: " + value);
    }

    /** 解析模型输出里的极性；不认识的值返回 {@code null}，由校验器整体判失败，不做兜底猜测。 */
    public static ConstraintPolarity fromWire(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        for (ConstraintPolarity polarity : values()) {
            if (polarity.wire.equals(normalized)) {
                return polarity;
            }
        }
        return null;
    }
}
