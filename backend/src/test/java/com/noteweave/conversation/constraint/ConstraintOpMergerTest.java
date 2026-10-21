package com.noteweave.conversation.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.conversation.ConversationConstraintProjectorV2;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 合并器的单元测试：只跑纯函数，不接触 Spring、数据库或模型。
 * 场景清单与 {@code design3.md} §4(b) 对齐。
 */
class ConstraintOpMergerTest {

    private static final double HIGH = 0.9;
    private static final double MIN_CONFIDENCE = 0.55;
    private static final String TOPIC = "topic-a";

    private final ConstraintOpMerger merger = new ConstraintOpMerger(MIN_CONFIDENCE);

    @Test
    void noopLeavesTheLedgerUntouched() {
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION)),
                step("m2", 2, noop())));

        assertThat(rows).hasSize(1);
        ConversationConstraintProjectorV2.Constraint row = rows.get(0);
        assertThat(row.id()).isEqualTo(ConstraintIds.of("m1", "language", 0));
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.invalidAfterSeq()).isNull();
        assertThat(row.supersededBy()).isNull();
        assertThat(row.scope()).isEqualTo("CONVERSATION");
    }

    @Test
    void restatementKeepsTheBroaderScopeAndDoesNotInsert() {
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION)),
                step("m2", 2, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CURRENT_TOPIC))));

        assertThat(rows).hasSize(1);
        ConversationConstraintProjectorV2.Constraint row = rows.get(0);
        assertThat(row.scope()).isEqualTo("CONVERSATION");
        assertThat(row.validFromSeq()).isEqualTo(1);
        assertThat(row.status()).isEqualTo("ACTIVE");
        assertThat(row.invalidAfterSeq()).isNull();
    }

    @Test
    void restatementAlsoRetiresOlderConflictingValuesInTheSameScope() {
        // "用中文"(本话题) → "以后用英文"(会话) → "继续用英文"(本话题重申)
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.LANGUAGE, "中文", ConstraintScope.CURRENT_TOPIC)),
                step("m2", 2, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION)),
                step("m3", 3, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CURRENT_TOPIC))));

        assertThat(rows).hasSize(2);
        ConversationConstraintProjectorV2.Constraint chinese = row(rows, ConstraintIds.of("m1", "language", 0));
        ConversationConstraintProjectorV2.Constraint english = row(rows, ConstraintIds.of("m2", "language", 0));
        // 会话级的英文行被重申，既不新插也不收窄。
        assertThat(english.status()).isEqualTo("ACTIVE");
        assertThat(english.scope()).isEqualTo("CONVERSATION");
        // 同话题里更老的中文行仍然被撤回，撤回指向被重申的那一行。
        assertThat(chinese.status()).isEqualTo("REVOKED");
        assertThat(chinese.invalidAfterSeq()).isEqualTo(3);
        assertThat(chinese.supersededBy()).isEqualTo(english.id());
    }

    @Test
    void differentSlotsCoexistWhileSameSlotValuesAreExclusive() {
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.TABLE, null, ConstraintScope.CURRENT_TOPIC),
                        add(ConstraintSlot.CONCLUSION, null, ConstraintScope.CURRENT_TOPIC)),
                step("m2", 2, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CURRENT_TOPIC)),
                step("m3", 3, add(ConstraintSlot.LANGUAGE, "中文", ConstraintScope.CURRENT_TOPIC))));

        assertThat(rows).hasSize(4);
        ConversationConstraintProjectorV2.Constraint table = row(rows, ConstraintIds.of("m1", "table", 0));
        ConversationConstraintProjectorV2.Constraint conclusion = row(rows, ConstraintIds.of("m1", "conclusion", 1));
        // 互补格式各自成行，kind 都是 FORMAT，槽位与取值不同。
        assertThat(table.status()).isEqualTo("ACTIVE");
        assertThat(conclusion.status()).isEqualTo("ACTIVE");
        assertThat(table.kind()).isEqualTo("FORMAT");
        assertThat(table.text()).isEqualTo("format.table");
        assertThat(conclusion.text()).isEqualTo("format.conclusion");

        // 同一语言槽位互斥：后一条顶掉前一条。
        ConversationConstraintProjectorV2.Constraint english = row(rows, ConstraintIds.of("m2", "language", 0));
        ConversationConstraintProjectorV2.Constraint chinese = row(rows, ConstraintIds.of("m3", "language", 0));
        assertThat(english.status()).isEqualTo("REVOKED");
        assertThat(english.invalidAfterSeq()).isEqualTo(3);
        assertThat(english.supersededBy()).isEqualTo(chinese.id());
        assertThat(chinese.status()).isEqualTo("ACTIVE");
        assertThat(chinese.kind()).isEqualTo("LANGUAGE");
    }

    @Test
    void explicitThisTurnNarrowsTheNewRowOnUpdate() {
        String englishId = ConstraintIds.of("m1", "language", 0);
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION)),
                step("m2", 2, update(ConstraintSlot.LANGUAGE, "中文", ConstraintScope.THIS_TURN, englishId))));

        ConversationConstraintProjectorV2.Constraint english = row(rows, englishId);
        ConversationConstraintProjectorV2.Constraint chinese = row(rows, ConstraintIds.of("m2", "language", 0));
        // 显式"这一次"是唯一能收窄的写法：会话级英文行被撤回，中文行只在本话题生效。
        assertThat(english.status()).isEqualTo("REVOKED");
        assertThat(english.invalidAfterSeq()).isEqualTo(2);
        assertThat(english.supersededBy()).isEqualTo(chinese.id());
        assertThat(chinese.status()).isEqualTo("ACTIVE");
        assertThat(chinese.scope()).isEqualTo("CURRENT_TOPIC");
    }

    @Test
    void undeclaredScopeKeepsTheOldWidthOnUpdate() {
        String englishId = ConstraintIds.of("m1", "language", 0);
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION)),
                step("m2", 2, update(ConstraintSlot.LANGUAGE, "中文", null, englishId))));

        ConversationConstraintProjectorV2.Constraint chinese = row(rows, ConstraintIds.of("m2", "language", 0));
        // 模型没声明 scope 时不降级：更正后的中文要求继承会话宽度。
        assertThat(chinese.status()).isEqualTo("ACTIVE");
        assertThat(chinese.scope()).isEqualTo("CONVERSATION");
        assertThat(row(rows, englishId).status()).isEqualTo("REVOKED");
    }

    @Test
    void updateRevokesAcrossScopes() {
        String tableId = ConstraintIds.of("m1", "table", 0);
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.TABLE, null, ConstraintScope.CURRENT_TOPIC)),
                step("m2", 2, update(ConstraintSlot.TABLE, null, ConstraintScope.CONVERSATION, tableId))));

        ConversationConstraintProjectorV2.Constraint old = row(rows, tableId);
        ConversationConstraintProjectorV2.Constraint replacement = row(rows, ConstraintIds.of("m2", "table", 0));
        // 本话题的行被会话级的更正撤回：这就是「跨 scope 撤回」。
        assertThat(old.status()).isEqualTo("REVOKED");
        assertThat(old.supersededBy()).isEqualTo(replacement.id());
        assertThat(replacement.status()).isEqualTo("ACTIVE");
        assertThat(replacement.scope()).isEqualTo("CONVERSATION");
    }

    @Test
    void revokeByTargetIdRetiresTheRowWithoutInserting() {
        String tableId = ConstraintIds.of("m1", "table", 0);
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.TABLE, null, ConstraintScope.CURRENT_TOPIC)),
                step("m2", 2, revoke(tableId))));

        assertThat(rows).hasSize(1);
        ConversationConstraintProjectorV2.Constraint revoked = rows.get(0);
        assertThat(revoked.id()).isEqualTo(tableId);
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.invalidAfterSeq()).isEqualTo(2);
        assertThat(revoked.supersededBy()).isNull();
    }

    @Test
    void revokeBySlotAndValueOnlyTouchesThatValue() {
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION)),
                step("m2", 2, add(ConstraintSlot.LANGUAGE, "中文", ConstraintScope.CURRENT_TOPIC)),
                step("m3", 3, revoke(ConstraintSlot.LANGUAGE, "英文"))));

        ConversationConstraintProjectorV2.Constraint english = row(rows, ConstraintIds.of("m1", "language", 0));
        ConversationConstraintProjectorV2.Constraint chinese = row(rows, ConstraintIds.of("m2", "language", 0));
        assertThat(english.status()).isEqualTo("REVOKED");
        assertThat(chinese.status()).isEqualTo("ACTIVE");
        assertThat(chinese.text()).isEqualTo("中文");
    }

    @Test
    void negationOnlyRevokesTheSameValue() {
        String englishId = ConstraintIds.of("m1", "language", 0);
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION)),
                step("m2", 2, add(ConstraintSlot.NEGATION, "中文", ConstraintScope.CURRENT_TOPIC)),
                step("m3", 3, add(ConstraintSlot.NEGATION, "中文", ConstraintScope.CURRENT_TOPIC))));

        // "不要中文"不撤"英文"：否定与正向取值不同，两条并存。
        assertThat(row(rows, englishId).status()).isEqualTo("ACTIVE");
        // 两条同值的否定要求互斥，后一条顶掉前一条。
        ConversationConstraintProjectorV2.Constraint first = row(rows, ConstraintIds.of("m2", "negation", 0));
        ConversationConstraintProjectorV2.Constraint second = row(rows, ConstraintIds.of("m3", "negation", 0));
        assertThat(first.status()).isEqualTo("REVOKED");
        assertThat(first.supersededBy()).isEqualTo(second.id());
        assertThat(second.status()).isEqualTo("ACTIVE");
        assertThat(second.kind()).isEqualTo("NEGATION");
    }

    @Test
    void negationWithoutValueStandsAlone() {
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.NEGATION, null, ConstraintScope.CURRENT_TOPIC)),
                step("m2", 2, add(ConstraintSlot.NEGATION, null, ConstraintScope.CURRENT_TOPIC)),
                step("m3", 3, revoke(ConstraintSlot.NEGATION, null))));

        // 没有取值的否定要求各自成行：既不撤回别人，也不被后续约束（包括 REVOKE）撤回。
        assertThat(rows).hasSize(2);
        for (ConversationConstraintProjectorV2.Constraint row : rows) {
            assertThat(row.status()).isEqualTo("ACTIVE");
            assertThat(row.invalidAfterSeq()).isNull();
        }
        assertThat(rows.get(0).id()).isEqualTo(ConstraintIds.of("m1", "negation", 0));
        assertThat(rows.get(1).id()).isEqualTo(ConstraintIds.of("m2", "negation", 0));
    }

    @Test
    void updateWithoutTargetDegradesToAdd() {
        String tableId = ConstraintIds.of("m1", "table", 0);
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.TABLE, null, ConstraintScope.CURRENT_TOPIC)),
                step("m2", 2, update(ConstraintSlot.TABLE, null, ConstraintScope.CURRENT_TOPIC, null))));

        ConversationConstraintProjectorV2.Constraint old = row(rows, tableId);
        ConversationConstraintProjectorV2.Constraint replacement = row(rows, ConstraintIds.of("m2", "table", 0));
        assertThat(old.status()).isEqualTo("REVOKED");
        assertThat(old.invalidAfterSeq()).isEqualTo(2);
        assertThat(old.supersededBy()).isEqualTo(replacement.id());
        assertThat(replacement.status()).isEqualTo("ACTIVE");
    }

    @Test
    void lowConfidenceOpsAreDropped() {
        List<ConversationConstraintProjectorV2.Constraint> belowThreshold = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.TABLE, null, ConstraintScope.CURRENT_TOPIC, 0.3))));
        assertThat(belowThreshold).isEmpty();

        String tableId = ConstraintIds.of("m1", "table", 0);
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1, add(ConstraintSlot.TABLE, null, ConstraintScope.CURRENT_TOPIC)),
                step("m2", 2, update(ConstraintSlot.TABLE, null, ConstraintScope.CONVERSATION, tableId, 0.4))));

        // 低置信度的 UPDATE 被丢弃，连旧行都不动。
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).status()).isEqualTo("ACTIVE");
        assertThat(rows.get(0).scope()).isEqualTo("CURRENT_TOPIC");
    }

    @Test
    void multipleOpsInOneStepApplyInOrder() {
        List<ConversationConstraintProjectorV2.Constraint> rows = merger.fold(List.of(
                step("m1", 1,
                        add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CURRENT_TOPIC),
                        add(ConstraintSlot.TABLE, null, ConstraintScope.CURRENT_TOPIC),
                        add(ConstraintSlot.LANGUAGE, "中文", ConstraintScope.CURRENT_TOPIC))));

        assertThat(rows).hasSize(3);
        ConversationConstraintProjectorV2.Constraint english = row(rows, ConstraintIds.of("m1", "language", 0));
        ConversationConstraintProjectorV2.Constraint table = row(rows, ConstraintIds.of("m1", "table", 1));
        ConversationConstraintProjectorV2.Constraint chinese = row(rows, ConstraintIds.of("m1", "language", 2));
        // 同一条消息里同槽位后者胜，不同槽位互不影响。
        assertThat(english.status()).isEqualTo("REVOKED");
        assertThat(english.supersededBy()).isEqualTo(chinese.id());
        assertThat(table.status()).isEqualTo("ACTIVE");
        assertThat(chinese.status()).isEqualTo("ACTIVE");
        assertThat(chinese.text()).isEqualTo("中文");
    }

    @Test
    void foldIsIdempotentAndIdsFitTheColumn() {
        List<ConstraintOpMerger.Step> steps = List.of(
                step("m1", 1, add(ConstraintSlot.LANGUAGE, "英文", ConstraintScope.CONVERSATION)),
                step("m2", 2, add(ConstraintSlot.TABLE, null, ConstraintScope.CURRENT_TOPIC)),
                step("m3", 3, add(ConstraintSlot.LANGUAGE, "中文", ConstraintScope.CURRENT_TOPIC)));

        List<ConversationConstraintProjectorV2.Constraint> first = merger.fold(steps);
        List<ConversationConstraintProjectorV2.Constraint> second = merger.fold(steps);

        // 同一个输入两次折叠结果完全一致（id、状态、撤回指针都稳定）。
        assertThat(second).isEqualTo(first);
        assertThat(first).extracting(ConversationConstraintProjectorV2.Constraint::id)
                .allSatisfy(id -> {
                    assertThat(id).startsWith("constraint-").hasSizeLessThanOrEqualTo(36);
                });

        // id 只依赖 (messageId, slot, index)：同 slot 不同下标、不同 slot 同下标都不撞号。
        assertThat(ConstraintIds.of("m1", "language", 0))
                .isNotEqualTo(ConstraintIds.of("m1", "language", 1))
                .isNotEqualTo(ConstraintIds.of("m1", "table", 0))
                .isNotEqualTo(ConstraintIds.of("m2", "language", 0))
                .isEqualTo(ConstraintIds.of("m1", "language", 0));
    }

    private static ConstraintOp add(ConstraintSlot slot, String value, ConstraintScope scope) {
        return add(slot, value, scope, HIGH);
    }

    private static ConstraintOp add(ConstraintSlot slot, String value, ConstraintScope scope, double confidence) {
        return new ConstraintOp(ConstraintOp.Op.ADD, slot, value, scope, null, confidence);
    }

    private static ConstraintOp update(ConstraintSlot slot, String value, ConstraintScope scope, String targetId) {
        return update(slot, value, scope, targetId, HIGH);
    }

    private static ConstraintOp update(ConstraintSlot slot, String value, ConstraintScope scope, String targetId,
            double confidence) {
        return new ConstraintOp(ConstraintOp.Op.UPDATE, slot, value, scope, targetId, confidence);
    }

    private static ConstraintOp revoke(String targetId) {
        return new ConstraintOp(ConstraintOp.Op.REVOKE, null, null, null, targetId, HIGH);
    }

    private static ConstraintOp revoke(ConstraintSlot slot, String value) {
        return new ConstraintOp(ConstraintOp.Op.REVOKE, slot, value, null, null, HIGH);
    }

    private static ConstraintOp noop() {
        return new ConstraintOp(ConstraintOp.Op.NOOP, null, null, null, null, HIGH);
    }

    private static ConstraintOpMerger.Step step(String messageId, int seq, ConstraintOp... ops) {
        return new ConstraintOpMerger.Step(messageId, seq, TOPIC, List.of(ops));
    }

    private static ConversationConstraintProjectorV2.Constraint row(
            List<ConversationConstraintProjectorV2.Constraint> rows, String id) {
        return rows.stream()
                .filter(candidate -> id.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到约束行：" + id));
    }
}
