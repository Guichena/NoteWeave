package com.noteweave.preference;

import com.noteweave.conversation.constraint.ConstraintSlot;

/**
 * F5-01：偏好的语义维度族。
 *
 * <p>族只用于归类与展示；真正判断“同一维度”用细粒度目标键。这样本轮术语 A 的要求
 * 不会清掉长期术语 B，也只有同一目标键上的不同极性才会互相覆盖。</p>
 */
public enum PreferenceSlotFamily {

    LANGUAGE("language"),
    LENGTH("length"),
    FORMAT("format"),
    TERMINOLOGY("terminology"),
    SOURCE_POLICY("source_policy"),
    NEGATION("negation");

    private final String wire;

    PreferenceSlotFamily(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 把现有约束槽位映射到偏好族；未知槽位返回 {@link #NEGATION} 之外的格式族。 */
    public static PreferenceSlotFamily fromConstraintSlot(ConstraintSlot slot) {
        if (slot == null) {
            return FORMAT;
        }
        return switch (slot) {
            case LANGUAGE -> LANGUAGE;
            case LENGTH_LIMIT, DETAIL -> LENGTH;
            case TABLE, BULLETS, CONCLUSION, CITATION, CODE -> FORMAT;
            case NEGATION -> NEGATION;
        };
    }

    /** 细粒度目标键：沿用约束内部槽位，保证同一语义维度只有一个键。 */
    public static String targetKey(ConstraintSlot slot) {
        return slot == null ? "unknown" : slot.internalSlot();
    }
}
