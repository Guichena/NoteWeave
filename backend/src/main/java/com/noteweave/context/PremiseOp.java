package com.noteweave.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 一条从用户消息里抽出的前提操作。
 *
 * <p>结构与长度在这里校验，语义（能否定位目标、能否覆盖）留给 {@link PremiseResolver}。
 * 操作只描述用户说了什么，不描述模型猜了什么：没有可靠目标的撤回不会被当成撤回执行。</p>
 *
 * @param action          操作类型
 * @param relation        当前问题与前提的关系
 * @param subjectKey      主体与属性
 * @param value           陈述取值，撤回时可为空串
 * @param scope           作用范围
 * @param topicId         话题身份，可为空串
 * @param targetPremiseId 显式撤回目标，可为空串
 * @param sourceMessageId 来源消息身份
 * @param sourceSeq       来源消息序号
 * @param extractionVersion 抽取版本
 * @param confidence      置信度，取值 0 到 1
 * @param index           同一消息内操作下标
 */
public record PremiseOp(
        Action action,
        Relation relation,
        String subjectKey,
        String value,
        ConversationPremise.PremiseScope scope,
        String topicId,
        String targetPremiseId,
        String sourceMessageId,
        int sourceSeq,
        String extractionVersion,
        double confidence,
        int index
) {

    /** 陈述取值长度上限，防止把长段正文塞进前提。 */
    public static final int MAX_VALUE_CHARS = 200;
    /** 置信度缺省值。 */
    public static final double DEFAULT_CONFIDENCE = 0.7d;
    /** 前提 id 前缀。 */
    static final String ID_PREFIX = "premise-";

    public PremiseOp {
        value = value == null ? "" : value.trim();
        topicId = topicId == null ? "" : topicId;
        targetPremiseId = targetPremiseId == null ? "" : targetPremiseId;
        if (action == null || relation == null || scope == null
                || subjectKey == null || subjectKey.isBlank()
                || sourceMessageId == null || sourceMessageId.isBlank()
                || sourceSeq < 1 || extractionVersion == null || extractionVersion.isBlank()
                || index < 0 || !(confidence >= 0.0d && confidence <= 1.0d)) {
            throw new IllegalArgumentException("premise operation is invalid");
        }
        if (value.codePointCount(0, value.length()) > MAX_VALUE_CHARS) {
            throw new IllegalArgumentException("premise value exceeds " + MAX_VALUE_CHARS + " characters");
        }
        if (action.writesValue() && value.isEmpty()) {
            throw new IllegalArgumentException("an asserted premise requires a value");
        }
    }

    /** 稳定前提 id：同一消息、同一主体、同一下标重放得到同一个 id。 */
    public String premiseId() {
        return ID_PREFIX + digest(sourceMessageId + ":" + subjectKey + ":" + index).substring(0, 24);
    }

    /** 幂等键：同一来源消息与同一抽取版本的同一操作只应用一次。 */
    public String idempotencyKey() {
        return sourceMessageId + "|" + extractionVersion + "|" + index;
    }

    /** 操作类型。 */
    public enum Action {
        /** 陈述一个取值。 */
        ASSERT("assert"),
        /** 更正同一主体同一作用域的旧取值。 */
        CORRECT("correct"),
        /** 撤回同一主体同一作用域的旧取值。 */
        RETRACT("retract");

        private final String wire;

        Action(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** 是否写入一个取值。 */
        public boolean writesValue() {
            return this == ASSERT || this == CORRECT;
        }

        /** 解析模型输出，不认识返回 null。 */
        public static Action fromWire(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Action action : values()) {
                if (action.wire.equals(normalized)) {
                    return action;
                }
            }
            return null;
        }
    }

    /** 当前问题与前提的关系。 */
    public enum Relation {
        /** 引用已有前提。 */
        REFERENCE("reference"),
        /** 把前提当成假设。 */
        ASSUMPTION("assumption"),
        /** 更正已有前提。 */
        CORRECTION("correction"),
        /** 转述他人的说法，不是用户自己的前提。 */
        THIRD_PARTY_STATEMENT("third-party");

        private final String wire;

        Relation(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** 解析模型输出，不认识返回 null。 */
        public static Relation fromWire(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Relation relation : values()) {
                if (relation.wire.equals(normalized)) {
                    return relation;
                }
            }
            return null;
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
