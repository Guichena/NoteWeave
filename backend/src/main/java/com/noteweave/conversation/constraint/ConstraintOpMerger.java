package com.noteweave.conversation.constraint;

import com.noteweave.conversation.ConversationConstraintProjectorV2;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把校验后的约束操作折叠成一份整表快照，是唯一的冲突引擎。
 * <p>
 * 纯函数：不做 IO、不读时钟、不依赖 Spring，同一个输入永远得到同一份输出。调用方按消息顺序把
 * {@link Step} 交给 {@link #fold(List)}，合并器维护「当前 ACTIVE 约束」，逐条应用 op 并给出
 * 被顶掉的行（{@code status=REVOKED}、{@code invalidAfterSeq=撤回消息的 seq}、{@code supersededBy=新行 id}）。
 * <p>
 * 只做下面这些判定，不再理解自然语言：
 * <ol>
 *   <li>{@code NOOP}：不入行，也不撤回任何东西。</li>
 *   <li>{@code ADD}：同槽位、同取值、且旧行 scope 更宽或相同时视为重申——保留旧行，不收窄、不新插；
 *       否则撤回同槽位同 scope 的旧 ACTIVE 行后插入新行。</li>
 *   <li>{@code UPDATE}：{@code targetId} 命中 ACTIVE 行时无条件撤回（允许跨 scope，这正是
 *       「以后用英文」→「改用中文」）；新行落在 {@code op.scope}，只有显式的 {@code this-turn}
 *       才收窄，未声明或 {@code current-topic} 时保留旧行的 scope 宽度（见 {@link #updateScope}）。
 *       没有 {@code targetId} 或 id 没命中时退化成 {@code ADD}。</li>
 *   <li>{@code REVOKE}：按 {@code targetId}（或 {@code slot}+{@code value}）撤回，不新增行。</li>
 *   <li>否定约束（{@code slot=negation}）只撤回同 slot 同取值的旧行；没有取值时单独成行，
 *       既不撤回别人，也不被后来的约束撤回。</li>
 *   <li>取值不同、且任一方是否定约束时不算冲突（{@code "不要中文"} 不撤 {@code "英文"}）。</li>
 *   <li>同一条消息的多个 op 按数组顺序应用，同槽位后者胜；{@code confidence} 低于门槛的
 *       {@code ADD}/{@code UPDATE} 直接丢弃。</li>
 * </ol>
 * <p>
 * 两点与设计文档的差异（以代码现状为准）：
 * <ul>
 *   <li>{@link ConstraintOp} 没有极性字段，因此「是否否定」只能由 {@code slot == NEGATION} 推导
 *       （见 {@link #forbid(ConstraintSlot)}）。提示词让模型对「不要用表格」给出 {@code slot=table}，
 *       这类带目标的否定在合并器看来与正向要求同形，无法区分；将来 {@code ConstraintOp} 补上极性字段后
 *       只需改 {@link #forbid(ConstraintSlot)} 与 {@link #conflicts(Active, ConstraintSlot, String)}。</li>
 *   <li>op 只有 {@code slot}/{@code value}，没有自由文本，因此新行的 {@code text} 取 {@code value}
 *       （缺失时取 {@link ConstraintSlot#wire()}）。规则路径的分句原文无法从 op 恢复，
 *       需要中文原文的渲染得在接线层另取消息片段。</li>
 * </ul>
 * <p>
 * 合并器永远不产出 {@code UNRESOLVED}：op 表达不了这个状态，指代不明的约束由规则路径在折叠之外单独成行。
 */
public final class ConstraintOpMerger {

    /** 合并规则版本：版本不同就说明两次重放的结论不可直接比较（例如后续改了冲突规则）。 */
    public static final String MERGER_VERSION = "constraint-merger-v1";

    /** 默认不做置信度过滤：接线时把 {@code constraint-op-min-confidence} 传进构造器。 */
    public static final double NO_CONFIDENCE_FLOOR = 0.0;

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String SCOPE_CURRENT_TOPIC = ConstraintScope.CURRENT_TOPIC.dbScope();
    private static final String SCOPE_CONVERSATION = ConstraintScope.CONVERSATION.dbScope();
    /** 当前话题的 scope 键要带话题 id：换话题后旧约束自然不再被新话题的要求顶掉。 */
    private static final String SCOPE_KEY_CURRENT_TOPIC = SCOPE_CURRENT_TOPIC + ":";

    private final double minConfidence;

    public ConstraintOpMerger() {
        this(NO_CONFIDENCE_FLOOR);
    }

    public ConstraintOpMerger(double minConfidence) {
        if (!(minConfidence >= 0.0 && minConfidence <= 1.0)) {
            throw new IllegalArgumentException("minConfidence 必须在 0 和 1 之间");
        }
        this.minConfidence = minConfidence;
    }

    /**
     * 按顺序折叠所有步骤，返回带 REVOKED 行的整表快照。
     *
     * @param steps 按会话顺序排列的步骤；{@code null} 或空列表返回空快照
     * @return 不可变的约束行列表，顺序等于行的创建顺序（被撤回的行留在原位）
     */
    public List<ConversationConstraintProjectorV2.Constraint> fold(List<Step> steps) {
        List<Row> rows = foldRows(steps);
        List<ConversationConstraintProjectorV2.Constraint> output = new ArrayList<>(rows.size());
        for (Row row : rows) {
            output.add(row.constraint());
        }
        return List.copyOf(output);
    }

    /**
     * 折叠到当前状态后仍然 ACTIVE 的行，供下一次模型调用作为 {@code targetId} 的候选。
     * <p>
     * 槽位与取值只存在于合并器内部（不入库），所以必须由合并器给出，不能从落库的 {@code kind} 反推。
     *
     * @param steps 已经应用过的步骤，按会话顺序
     * @return 不可变的 ACTIVE 约束列表，顺序等于行的创建顺序
     */
    public List<ConstraintOpsExtractor.ActiveConstraint> activeConstraints(List<Step> steps) {
        List<ConstraintOpsExtractor.ActiveConstraint> active = new ArrayList<>();
        for (Row row : foldRows(steps)) {
            ConversationConstraintProjectorV2.Constraint constraint = row.constraint();
            if (!STATUS_ACTIVE.equals(constraint.status())) {
                continue;
            }
            active.add(new ConstraintOpsExtractor.ActiveConstraint(constraint.id(), row.slot(), row.value(),
                    constraint.scope(), constraint.text(), constraint.validFromSeq()));
        }
        return List.copyOf(active);
    }

    private List<Row> foldRows(List<Step> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<Row> rows = new ArrayList<>();
        for (Step step : steps) {
            if (step == null) {
                continue;
            }
            List<ConstraintOp> ops = step.ops();
            for (int index = 0; index < ops.size(); index++) {
                ConstraintOp op = ops.get(index);
                if (op == null || op.op() == ConstraintOp.Op.NOOP) {
                    continue;
                }
                if (op.op() == ConstraintOp.Op.REVOKE) {
                    revoke(rows, step, op);
                } else if (op.op() == ConstraintOp.Op.UPDATE) {
                    update(rows, step, op, index);
                } else {
                    add(rows, step, op, index);
                }
            }
        }
        return List.copyOf(rows);
    }

    /** ADD：重申判定 → 同 scope 顶掉同槽位旧值 → 插入新行。 */
    private void add(List<Row> rows, Step step, ConstraintOp op, int index) {
        if (op.confidence() < minConfidence) {
            return;
        }
        ConstraintSlot slot = op.slot();
        if (slot == null) {
            return;
        }
        String value = valueOf(slot, op);
        if (slot == ConstraintSlot.LANGUAGE && value == null) {
            // 没有说清用哪种语言的要求落不了行，记下来只会是一条空约束。
            return;
        }
        String scope = scopeOf(op.scope());
        String scopeKey = scopeKey(scope, step.topicId());
        String id = ConstraintIds.of(step.messageId(), slot.wire(), index);
        List<Active> active = actives(rows);
        if (slot == ConstraintSlot.NEGATION && value == null) {
            // 没有指向具体线索的否定要求单独成行：不撤回旧约束，也不会被后来的约束撤回。
            rows.add(row(id, step, slot, value, scope));
            return;
        }
        Active restated = restated(active, slot, value, scopeKey);
        if (restated != null) {
            // 重申同值：保留旧行（包括更宽的 scope），既不新插也不收窄；
            // 但同 scope 里更老的冲突取值仍要撤回，否则会同时生效两条互斥要求。
            retireConflicts(rows, active, step.seq(), slot, value, scopeKey, restated);
            return;
        }
        for (Active previous : active) {
            if (revocable(previous, slot, value, scopeKey)) {
                retire(rows, step.seq(), previous, id);
            }
        }
        rows.add(row(id, step, slot, value, scope));
    }

    /** UPDATE：命中 targetId 时跨 scope 撤回旧行，再按 op.scope 落新行；否则退化成 ADD。 */
    private void update(List<Row> rows, Step step, ConstraintOp op, int index) {
        if (op.confidence() < minConfidence) {
            return;
        }
        ConstraintSlot slot = op.slot();
        if (slot == null) {
            return;
        }
        List<Active> active = actives(rows);
        Active target = find(active, op.targetId());
        if (target == null) {
            // 模型没给 id 或给了编造的 id：按新增处理，仍然顶掉同槽位同 scope 的旧值。
            add(rows, step, op, index);
            return;
        }
        String value = valueOf(slot, op);
        if (slot == ConstraintSlot.LANGUAGE && value == null) {
            return;
        }
        String id = ConstraintIds.of(step.messageId(), slot.wire(), index);
        retire(rows, step.seq(), target, id);
        rows.add(row(id, step, slot, value, updateScope(op.scope(), target)));
    }

    /** REVOKE：先按 id，再按 slot（可带 value）撤回，不新增行。 */
    private void revoke(List<Row> rows, Step step, ConstraintOp op) {
        List<Active> active = actives(rows);
        Active target = find(active, op.targetId());
        if (target != null) {
            retire(rows, step.seq(), target, null);
            return;
        }
        if (op.slot() == null) {
            // 既没命中 id 也没给槽位：撤回目标不确定，什么也不做（校验器已挡掉，这里只兜底）。
            return;
        }
        for (Active previous : active) {
            if (previous.slot() != op.slot() || standalone(previous)) {
                continue;
            }
            if (op.value() != null && !op.value().equals(previous.value())) {
                continue;
            }
            retire(rows, step.seq(), previous, null);
        }
    }

    /**
     * 新取值是否是在重申旧值：同槽位、同取值，且旧行 scope 更宽或相同。
     * 否定约束不参与重申——两次「不要再贴大段配置」是两条要求，不是确认。
     */
    private static Active restated(List<Active> active, ConstraintSlot slot, String value, String scopeKey) {
        if (slot == ConstraintSlot.NEGATION || value == null) {
            return null;
        }
        for (Active previous : active) {
            if (previous.slot() != slot || previous.forbid()) {
                continue;
            }
            if (!value.equals(previous.value()) || !covers(previous.scopeKey(), scopeKey)) {
                continue;
            }
            return previous;
        }
        return null;
    }

    /** 旧行 scope 是否覆盖新行 scope：相同，或旧行是会话级而新行只是本话题。 */
    private static boolean covers(String previousScopeKey, String scopeKey) {
        if (previousScopeKey.equals(scopeKey)) {
            return true;
        }
        return SCOPE_CONVERSATION.equals(previousScopeKey) && !SCOPE_CONVERSATION.equals(scopeKey);
    }

    /** 同槽位、同 scope、未失效，且不属于「否定不同值」的行才能被新值顶掉。 */
    private static boolean revocable(Active previous, ConstraintSlot slot, String value, String scopeKey) {
        if (previous.slot() != slot || standalone(previous)) {
            return false;
        }
        // 否定约束只撤回同值：'不要中文' 不撤 '英文'，另一个 '不要中文' 才顶掉它。
        if (conflicts(previous, slot, value)) {
            return false;
        }
        return previous.scopeKey().equals(scopeKey);
    }

    /** 取值不同、且任一方是否定约束：不是互斥冲突，两条并存。 */
    private static boolean conflicts(Active previous, ConstraintSlot slot, String value) {
        if (!previous.forbid() && !forbid(slot)) {
            return false;
        }
        return previous.value() != null && value != null && !previous.value().equals(value);
    }

    /**
     * 重申时撤回同 scope 里其它冲突取值。保留被重申的那一行，
     * 撤回行的 {@code supersededBy} 指向它，保证「确认」不会留下两条互斥要求。
     */
    private static void retireConflicts(List<Row> rows, List<Active> active, int seq, ConstraintSlot slot,
            String value, String scopeKey, Active keep) {
        for (Active previous : active) {
            if (previous.index() == keep.index()) {
                continue;
            }
            if (revocable(previous, slot, value, scopeKey)) {
                retire(rows, seq, previous, keep.id());
            }
        }
    }

    private static void retire(List<Row> rows, int seq, Active previous, String supersededBy) {
        Row row = rows.get(previous.index());
        ConversationConstraintProjectorV2.Constraint old = row.constraint();
        rows.set(previous.index(), new Row(new ConversationConstraintProjectorV2.Constraint(
                old.id(), old.sourceMessageId(), old.kind(), old.scope(), old.text(),
                old.validFromSeq(), seq, STATUS_REVOKED, supersededBy),
                row.slot(), row.value(), row.scopeKey()));
    }

    /** 当前 ACTIVE 行，按插入顺序；每个 op 应用前重算一次，避免拿着过期的索引改表。 */
    private static List<Active> actives(List<Row> rows) {
        List<Active> active = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (!STATUS_ACTIVE.equals(row.constraint().status())) {
                continue;
            }
            active.add(new Active(index, row.constraint().id(), row.slot(), row.value(),
                    forbid(row.slot()), row.scopeKey()));
        }
        return active;
    }

    private static Active find(List<Active> active, String id) {
        if (id == null) {
            return null;
        }
        for (Active item : active) {
            if (id.equals(item.id())) {
                return item;
            }
        }
        return null;
    }

    /**
     * 该槽位是否表示否定要求。op 没有极性字段，只能由槽位推导（见类注释）；
     * 将来 {@link ConstraintOp} 加极性时改这一处即可。
     */
    private static boolean forbid(ConstraintSlot slot) {
        return slot == ConstraintSlot.NEGATION;
    }

    /** 没有取值的否定行是独立的一条要求：既不撤回别人，也不会被撤回。 */
    private static boolean standalone(Active previous) {
        return previous.forbid() && previous.value() == null;
    }

    /**
     * 取值缺失时按槽位补：格式槽位的取值就是槽位本身（与规则路径一致，便于同槽同值判定）；
     * 语言与否定没有取值就不补，交给调用点决定丢弃还是单独成行。
     */
    private static String valueOf(ConstraintSlot slot, ConstraintOp op) {
        if (op.value() != null) {
            return op.value();
        }
        if (slot == null || slot == ConstraintSlot.LANGUAGE || slot == ConstraintSlot.NEGATION) {
            return null;
        }
        return slot.internalSlot();
    }

    /** ADD 未声明 scope 时不升级：只在本话题生效。 */
    private static String scopeOf(ConstraintScope scope) {
        return scope == null ? SCOPE_CURRENT_TOPIC : scope.dbScope();
    }

    /**
     * UPDATE 落库的 scope：只有显式的 {@code this-turn} 能收窄；
     * 未声明或 {@code current-topic} 时保留旧行的宽度（会话级偏好不会被一句本话题的话降级）。
     */
    private static String updateScope(ConstraintScope requested, Active target) {
        if (requested == ConstraintScope.CONVERSATION) {
            return SCOPE_CONVERSATION;
        }
        if (requested == ConstraintScope.THIS_TURN) {
            return SCOPE_CURRENT_TOPIC;
        }
        return SCOPE_CONVERSATION.equals(target.scopeKey()) ? SCOPE_CONVERSATION : SCOPE_CURRENT_TOPIC;
    }

    private static String scopeKey(String scope, String topicId) {
        return SCOPE_CURRENT_TOPIC.equals(scope)
                ? SCOPE_KEY_CURRENT_TOPIC + (topicId == null ? "" : topicId)
                : scope;
    }

    private static Row row(String id, Step step, ConstraintSlot slot, String value, String scope) {
        String text = value == null ? slot.wire() : value;
        return new Row(new ConversationConstraintProjectorV2.Constraint(
                id, step.messageId(), slot.dbKind(), scope, text, step.seq(), null, STATUS_ACTIVE, null),
                slot, value, scopeKey(scope, step.topicId()));
    }

    /**
     * 一条消息的输入：模型（或规则兜底）给出的 ops 已经过校验。
     *
     * @param messageId 源消息 id，用来生成稳定 id
     * @param seq       消息序号，撤回行写进 {@code invalid_after_seq}
     * @param topicId   当前话题 id，用于 {@code CURRENT_TOPIC} 的 scope 隔离
     * @param ops       校验后的操作，按用户表达顺序
     */
    public record Step(String messageId, int seq, String topicId, List<ConstraintOp> ops) {

        public Step {
            Objects.requireNonNull(messageId, "messageId");
            ops = ops == null ? List.of() : List.copyOf(ops);
        }
    }

    /** 内部行：约束行本身加上只用于冲突判定的槽位/取值/scope 键（都不入库）。 */
    private record Row(ConversationConstraintProjectorV2.Constraint constraint, ConstraintSlot slot,
                       String value, String scopeKey) {
    }

    /** 当前仍然生效的行：索引、id、槽位、取值、是否否定、scope 键。 */
    private record Active(int index, String id, ConstraintSlot slot, String value, boolean forbid,
                          String scopeKey) {
    }
}
