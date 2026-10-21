package com.noteweave.conversation.constraint;

import java.util.Locale;

/**
 * 约束抽取模型可用的槽位。
 * <p>
 * 三个名字各有用途，不要混用：
 * <ul>
 *   <li>{@link #wire()}：模型输出与提示词里用的字面量（{@code language}、{@code length-limit}）；</li>
 *   <li>{@link #internalSlot()}：投影器内部的语义槽位（{@code answer.language}、{@code format.length.max}），
 *       只用于判断同槽互斥，不入库；</li>
 *   <li>{@link #dbKind()}：落库的 {@code conversation_constraint_v2.kind}，只有 LANGUAGE/FORMAT/NEGATION 三值。</li>
 * </ul>
 * 内部槽位名沿用 M15 时期的命名，保证同一句话在提示词与合并器里指向同一个槽位。
 */
public enum ConstraintSlot {

    LANGUAGE("language", "answer.language", "LANGUAGE"),
    TABLE("table", "format.table", "FORMAT"),
    CONCLUSION("conclusion", "format.conclusion", "FORMAT"),
    BULLETS("bullets", "format.bullets", "FORMAT"),
    /** 字数上限（不超过N字/控制在N字/字数），与 DETAIL 是两个独立维度。 */
    LENGTH_LIMIT("length-limit", "format.length.max", "FORMAT"),
    /** 详略（简短/简洁/精简 vs 详细/展开），与 LENGTH_LIMIT 是两个独立维度。 */
    DETAIL("detail", "format.length.detail", "FORMAT"),
    CITATION("citation", "format.citation", "FORMAT"),
    CODE("code", "format.code", "FORMAT"),
    /**
     * 无目标的否定要求（"不要再贴大段配置"）；有目标时用被否定线索的槽位。
     * 内部槽位只是前缀：合并器会给无目标的否定接上消息 id（{@code negation:<messageId>}），
     * 让这类要求各自成行、互不撤回。
     */
    NEGATION("negation", "negation", "NEGATION");

    private final String wire;
    private final String internalSlot;
    private final String dbKind;

    ConstraintSlot(String wire, String internalSlot, String dbKind) {
        this.wire = wire;
        this.internalSlot = internalSlot;
        this.dbKind = dbKind;
    }

    /** 提示词与模型输出里的字面量。 */
    public String wire() {
        return wire;
    }

    /** 投影器内部槽位，只用于冲突判定。 */
    public String internalSlot() {
        return internalSlot;
    }

    /** 落库的 {@code kind}。 */
    public String dbKind() {
        return dbKind;
    }

    /**
     * 禁止项的渲染文本：{@code constraint_text} 直接存这句话，回答提示词与检索改写都按行渲染。
     * <p>
     * 只用于 {@code polarity=FORBID}（以及 {@link #NEGATION} 槽位）；正向槽位仍用取值本身。
     * 不能把内部槽位名（{@code format.table}）写进提示词，否则回答模型看到的只是一条正向要求。
     */
    public String forbidText(String value) {
        return switch (this) {
            case LANGUAGE -> value == null ? "不要使用这种语言" : "不要使用" + value;
            case TABLE -> "不要使用表格";
            case CONCLUSION -> "不要先给结论";
            case BULLETS -> "不要使用分点";
            case LENGTH_LIMIT -> "不要限制字数";
            case DETAIL -> "不要展开细节";
            case CITATION -> "不要标注来源";
            case CODE -> "不要给出代码";
            case NEGATION -> value == null ? "不要这样做" : "不要" + value;
        };
    }

    /**
     * 解析模型输出里的槽位；不认识的值返回 {@code null}，由校验器整体判失败，不做兜底猜测。
     * 大小写与下划线写法都接受（{@code LENGTH_LIMIT}、{@code length_limit}），便于不同模型稳定输出。
     */
    public static ConstraintSlot fromWire(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.isEmpty()) {
            return null;
        }
        for (ConstraintSlot slot : values()) {
            if (slot.wire.equals(normalized)) {
                return slot;
            }
        }
        return null;
    }
}
