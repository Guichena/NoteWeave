package com.noteweave.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前提折叠：把抽取出的操作按来源消息与抽取版本幂等地折叠成前提行。
 *
 * <p>规则来自架构设计第 7.3 节：确定的更正覆盖同主体同作用域的旧前提；跨话题或指代不明时
 * 不自动撤回；撤回必须能定位到唯一目标，否则记录为未解决且不改动任何前提；同一来源消息的同一
 * 抽取版本只应用一次。</p>
 *
 * <p>这里复用既有约束折叠的时序与幂等思路，但前提不写进约束槽位：主体是自由属性名，
 * 不是固定的偏好维度。</p>
 */
public final class PremiseResolver {

    /** 折叠实现版本。 */
    public static final String RESOLVER_VERSION = "premise-resolver-v1";

    /** 没有可靠目标，未执行。 */
    public static final String REASON_NO_RELIABLE_TARGET = "NO_RELIABLE_TARGET";
    /** 同一来源消息与抽取版本已经应用过。 */
    public static final String REASON_ALREADY_APPLIED = "ALREADY_APPLIED";
    /** 只是引用，不产生新前提。 */
    public static final String REASON_REFERENCE_ONLY = "REFERENCE_ONLY";
    /** 转述他人说法，不记为使用者自己的前提。 */
    public static final String REASON_THIRD_PARTY_STATEMENT = "THIRD_PARTY_STATEMENT";

    /** 折叠一次抽取。 */
    public Resolution resolve(Input input) {
        if (input == null) {
            throw new IllegalArgumentException("premise resolution requires an input");
        }
        Map<String, ConversationPremise> state = new LinkedHashMap<>();
        for (ConversationPremise premise : input.existing()) {
            state.put(premise.premiseId(), premise);
        }
        List<ConversationPremise> inserted = new ArrayList<>();
        List<ConversationPremise> updated = new ArrayList<>();
        List<Rejection> rejections = new ArrayList<>();
        List<PremiseOp> ops = new ArrayList<>(input.ops());
        ops.sort(java.util.Comparator.comparingInt(PremiseOp::sourceSeq)
                .thenComparingInt(PremiseOp::index));
        java.util.Set<String> appliedSources = new java.util.HashSet<>();
        for (ConversationPremise premise : input.existing()) {
            appliedSources.add(premise.sourceMessageId() + "|" + premise.extractionVersion());
        }
        java.util.Set<String> seenOps = new java.util.HashSet<>();
        for (PremiseOp op : ops) {
            String sourceKey = op.sourceMessageId() + "|" + op.extractionVersion();
            if (appliedSources.contains(sourceKey)) {
                rejections.add(new Rejection(op.idempotencyKey(), REASON_ALREADY_APPLIED,
                        "source message already folded for this extraction version"));
                continue;
            }
            if (!seenOps.add(op.idempotencyKey())) {
                rejections.add(new Rejection(op.idempotencyKey(), REASON_ALREADY_APPLIED,
                        "duplicate operation inside the same extraction"));
                continue;
            }
            if (op.relation() == PremiseOp.Relation.REFERENCE) {
                rejections.add(new Rejection(op.idempotencyKey(), REASON_REFERENCE_ONLY,
                        "reference does not assert a new value"));
                continue;
            }
            if (op.relation() == PremiseOp.Relation.THIRD_PARTY_STATEMENT) {
                rejections.add(new Rejection(op.idempotencyKey(), REASON_THIRD_PARTY_STATEMENT,
                        "third party statements are not the user premise"));
                continue;
            }
            ConversationPremise target = resolveTarget(state, op);
            if (op.action() == PremiseOp.Action.RETRACT) {
                if (target == null) {
                    rejections.add(new Rejection(op.idempotencyKey(), REASON_NO_RELIABLE_TARGET,
                            "retraction requires exactly one effective target"));
                    continue;
                }
                ConversationPremise revoked = new ConversationPremise(target.premiseId(),
                        target.workspaceId(), target.conversationId(), target.subjectKey(), "",
                        target.sourceMessageId(), target.sourceSeq(), target.scope(), target.topicId(),
                        target.validFromSeq(), op.sourceSeq(), ConversationPremise.STATUS_REVOKED,
                        target.supersededBy(), target.extractionVersion());
                state.put(revoked.premiseId(), revoked);
                updated.add(revoked);
                continue;
            }
            String newId = op.premiseId();
            String status = ConversationPremise.STATUS_ACTIVE;
            if (op.action() == PremiseOp.Action.CORRECT && target == null) {
                status = ConversationPremise.STATUS_UNRESOLVED;
                rejections.add(new Rejection(op.idempotencyKey(), REASON_NO_RELIABLE_TARGET,
                        "correction without a reliable target is recorded but not applied"));
            }
            if (target != null) {
                ConversationPremise superseded = new ConversationPremise(target.premiseId(),
                        target.workspaceId(), target.conversationId(), target.subjectKey(),
                        target.premiseText(), target.sourceMessageId(), target.sourceSeq(),
                        target.scope(), target.topicId(), target.validFromSeq(), op.sourceSeq(),
                        ConversationPremise.STATUS_SUPERSEDED, newId, target.extractionVersion());
                state.put(superseded.premiseId(), superseded);
                updated.add(superseded);
            }
            ConversationPremise created = new ConversationPremise(newId, input.workspaceId(),
                    input.conversationId(), op.subjectKey(), op.value(), op.sourceMessageId(),
                    op.sourceSeq(), op.scope(), op.topicId(), op.sourceSeq(), null, status, "",
                    op.extractionVersion());
            state.put(created.premiseId(), created);
            inserted.add(created);
        }
        return new Resolution(List.copyOf(inserted), List.copyOf(updated), List.copyOf(rejections),
                List.copyOf(state.values()));
    }

    private ConversationPremise resolveTarget(Map<String, ConversationPremise> state, PremiseOp op) {
        if (!op.targetPremiseId().isBlank()) {
            ConversationPremise explicit = state.get(op.targetPremiseId());
            if (explicit == null || !explicit.effectiveAt(op.sourceSeq())
                    || !explicit.subjectKey().equals(op.subjectKey())
                    || !sameScope(explicit, op)) {
                return null;
            }
            return explicit;
        }
        List<ConversationPremise> candidates = new ArrayList<>();
        for (ConversationPremise premise : state.values()) {
            if (premise.effectiveAt(op.sourceSeq())
                    && premise.subjectKey().equals(op.subjectKey())
                    && sameScope(premise, op)) {
                candidates.add(premise);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /** 同主体之外还要求同作用域；话题范围的前提只在同一话题内互相覆盖。 */
    private boolean sameScope(ConversationPremise premise, PremiseOp op) {
        if (premise.scope() != op.scope()) {
            return false;
        }
        return premise.scope() == ConversationPremise.PremiseScope.CONVERSATION
                || premise.topicId().equals(op.topicId());
    }

    /**
     * 折叠输入。
     *
     * @param workspaceId    工作台身份
     * @param conversationId 会话身份
     * @param existing       已有前提行
     * @param ops            本次抽取出的操作
     */
    public record Input(String workspaceId, String conversationId,
                        List<ConversationPremise> existing, List<PremiseOp> ops) {
        public Input {
            existing = existing == null ? List.of() : List.copyOf(existing);
            ops = ops == null ? List.of() : List.copyOf(ops);
            if (workspaceId == null || workspaceId.isBlank()
                    || conversationId == null || conversationId.isBlank()) {
                throw new IllegalArgumentException("premise resolution requires workspace and conversation");
            }
        }
    }

    /**
     * 折叠结果。
     *
     * @param inserted   新增行
     * @param updated    被取代或撤回的行
     * @param rejections 未执行的操作及原因
     * @param state      折叠后的全部前提行
     */
    public record Resolution(List<ConversationPremise> inserted, List<ConversationPremise> updated,
                             List<Rejection> rejections, List<ConversationPremise> state) {
        public Resolution {
            inserted = inserted == null ? List.of() : List.copyOf(inserted);
            updated = updated == null ? List.of() : List.copyOf(updated);
            rejections = rejections == null ? List.of() : List.copyOf(rejections);
            state = state == null ? List.of() : List.copyOf(state);
        }

        /** 给定截止序号上生效的前提，按来源序号升序。 */
        public List<ConversationPremise> effectiveAt(int cutoffSeq) {
            return state.stream()
                    .filter(premise -> premise.effectiveAt(cutoffSeq))
                    .sorted(java.util.Comparator.comparingInt(ConversationPremise::sourceSeq)
                            .thenComparing(ConversationPremise::premiseId))
                    .toList();
        }
    }

    /**
     * 未执行的操作。
     *
     * @param opKey  幂等键
     * @param reason 稳定原因码
     * @param detail 可读补充
     */
    public record Rejection(String opKey, String reason, String detail) {
        public Rejection {
            detail = detail == null ? "" : detail;
            if (opKey == null || opKey.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("premise rejection requires an operation and reason");
            }
        }
    }
}
