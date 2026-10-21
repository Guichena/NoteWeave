package com.noteweave.conversation.constraint;

import java.util.Locale;

/**
 * 约束的作用范围。
 * <p>
 * 模型侧有三个值，落库只有两个：{@code ContextWindowPlannerV2} 不认 {@code THIS_TURN}，
 * 直接持久化会被静默丢弃；本轮语义上它等价于本话题，换话题后丢弃也是期望行为，
 * 因此 {@link #dbScope()} 把 {@link #THIS_TURN} 折成 {@code CURRENT_TOPIC}。
 */
public enum ConstraintScope {

    /** 只针对本次回答。 */
    THIS_TURN("this-turn", "CURRENT_TOPIC"),
    /** 只针对当前话题，换话题即失效。 */
    CURRENT_TOPIC("current-topic", "CURRENT_TOPIC"),
    /** 整个会话（长期措辞：以后/每次/始终/从现在起）。 */
    CONVERSATION("conversation", "CONVERSATION");

    private final String wire;
    private final String dbScope;

    ConstraintScope(String wire, String dbScope) {
        this.wire = wire;
        this.dbScope = dbScope;
    }

    /** 提示词与模型输出里的字面量。 */
    public String wire() {
        return wire;
    }

    /** 落库的 {@code scope}，只会是 {@code CURRENT_TOPIC} 或 {@code CONVERSATION}。 */
    public String dbScope() {
        return dbScope;
    }

    /** 解析模型输出里的作用范围；不认识的值返回 {@code null}。 */
    public static ConstraintScope fromWire(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.isEmpty()) {
            return null;
        }
        for (ConstraintScope scope : values()) {
            if (scope.wire.equals(normalized)) {
                return scope;
            }
        }
        return null;
    }
}
