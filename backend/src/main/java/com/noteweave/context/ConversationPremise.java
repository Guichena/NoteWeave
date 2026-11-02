package com.noteweave.context;

import java.util.Set;

/**
 * 会话前提：用户在对话里陈述、但未经资料核实的条件。
 *
 * <p>前提是独立语义，不复用固定偏好槽位，也不登记引用、不晋升成长期事实记忆。渲染时必须标明
 * 未经核实，并与已核实的证据区分开。</p>
 *
 * <p>subjectKey 描述被陈述的实体与属性：数据库版本、预算上限与团队人数是三个不同的主体，
 * 不能互相替换。确定的更正只覆盖同主体同作用域的前提，跨话题或指代不明时不自动撤回。</p>
 *
 * @param premiseId        前提身份
 * @param workspaceId      工作台身份
 * @param conversationId   会话身份
 * @param subjectKey       主体与属性
 * @param premiseText      用户陈述文本
 * @param sourceMessageId  来源消息身份
 * @param sourceSeq        来源消息序号
 * @param scope            作用范围
 * @param topicId          话题身份，可为空串
 * @param validFromSeq     生效起始序号
 * @param invalidAfterSeq  失效序号，可为空
 * @param status           状态
 * @param supersededBy     被哪条前提取代，可为空串
 * @param extractionVersion 抽取版本
 */
public record ConversationPremise(
        String premiseId,
        String workspaceId,
        String conversationId,
        String subjectKey,
        String premiseText,
        String sourceMessageId,
        int sourceSeq,
        PremiseScope scope,
        String topicId,
        int validFromSeq,
        Integer invalidAfterSeq,
        String status,
        String supersededBy,
        String extractionVersion
) {

    /** 生效中。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 已被同主体同作用域的更正取代。 */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    /** 来源消息被删除或用户显式撤回。 */
    public static final String STATUS_REVOKED = "REVOKED";
    /** 没有可靠目标，未执行撤回或更正。 */
    public static final String STATUS_UNRESOLVED = "UNRESOLVED";

    /** 允许的状态。 */
    public static final Set<String> STATUSES = Set.of(
            STATUS_ACTIVE, STATUS_SUPERSEDED, STATUS_REVOKED, STATUS_UNRESOLVED);

    public ConversationPremise {
        topicId = topicId == null ? "" : topicId;
        supersededBy = supersededBy == null ? "" : supersededBy;
        premiseText = premiseText == null ? "" : premiseText;
        if (premiseId == null || premiseId.isBlank() || workspaceId == null || workspaceId.isBlank()
                || conversationId == null || conversationId.isBlank()
                || subjectKey == null || subjectKey.isBlank()
                || sourceMessageId == null || sourceMessageId.isBlank()
                || sourceSeq < 1 || scope == null || validFromSeq < 1
                || (invalidAfterSeq != null && invalidAfterSeq < validFromSeq)
                || !STATUSES.contains(status) || extractionVersion == null
                || extractionVersion.isBlank()) {
            throw new IllegalArgumentException("conversation premise is invalid");
        }
        if (STATUS_REVOKED.equals(status) && !premiseText.isEmpty()) {
            throw new IllegalArgumentException("a revoked premise cannot keep its text");
        }
    }

    /** 该前提在给定截止序号上是否生效。 */
    public boolean effectiveAt(int cutoffSeq) {
        return STATUS_ACTIVE.equals(status) && validFromSeq <= cutoffSeq
                && (invalidAfterSeq == null || invalidAfterSeq > cutoffSeq);
    }

    /** 渲染成未经核实的条件，不带引用编号。 */
    public String renderAsCondition() {
        return subjectKey + "：" + premiseText
                + "（第" + sourceSeq + "条陈述，未经资料核实）";
    }

    /** 作用范围。 */
    public enum PremiseScope {
        /** 只针对本轮。 */
        THIS_TURN("this-turn", "THIS_TURN"),
        /** 只针对当前话题，换话题即不再适用。 */
        CURRENT_TOPIC("current-topic", "CURRENT_TOPIC"),
        /** 整个会话。 */
        CONVERSATION("conversation", "CONVERSATION");

        private final String wire;
        private final String dbScope;

        PremiseScope(String wire, String dbScope) {
            this.wire = wire;
            this.dbScope = dbScope;
        }

        /** 提示词与模型输出里的字面量。 */
        public String wire() {
            return wire;
        }

        /** 落库取值。 */
        public String dbScope() {
            return dbScope;
        }

        /** 解析模型输出，不认识返回 null。 */
        public static PremiseScope fromWire(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            for (PremiseScope scope : values()) {
                if (scope.wire.equals(normalized)) {
                    return scope;
                }
            }
            return null;
        }

        /** 解析落库取值，不认识返回 null。 */
        public static PremiseScope fromDb(String value) {
            if (value == null) {
                return null;
            }
            for (PremiseScope scope : values()) {
                if (scope.dbScope.equals(value)) {
                    return scope;
                }
            }
            return null;
        }
    }
}
