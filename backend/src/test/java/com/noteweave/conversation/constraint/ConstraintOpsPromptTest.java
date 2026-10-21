package com.noteweave.conversation.constraint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 约束抽取提示词的单元测试：只读常量并把示例 ops 交给真实合并器折叠，
 * 不启动 Spring、不调用模型、不碰数据库。
 * <p>
 * 锁住 live 评测暴露的两个失败点：
 * <ul>
 *   <li>{@code update-this-turn-narrows}：同槽位已有会话级约束时，「这次改用 X」要落成
 *       UPDATE + targetId + this-turn，而不是另加一条话题级约束；</li>
 *   <li>{@code chinese-then-english-then-keep-english}：同值重申只算确认，不得新增更窄的 scope。</li>
 * </ul>
 */
class ConstraintOpsPromptTest {

    private static final double HIGH = 0.9;
    private static final double MIN_CONFIDENCE = 0.55;
    private static final String TOPIC = "report";

    private final ConstraintOpMerger merger = new ConstraintOpMerger(MIN_CONFIDENCE);

    @Test
    void narrowingCorrectionOnTheSameSlotIsTaughtAsUpdateWithTargetAndThisTurn() {
        String prompt = ConstraintOpsPrompt.SYSTEM_PROMPT;

        // 同槽位已有 ACTIVE 约束时必须有明确的三选一判定，否则模型看到「这次」就新加一条
        assertThat(prompt).contains("同槽位已经有 ACTIVE 约束");
        // 「改用 + 这次」的示例落成 UPDATE + targetId + this-turn
        assertThat(prompt).contains("\"op\":\"UPDATE\",\"slot\":\"language\",\"value\":\"中文\"");
        assertThat(prompt).contains("\"scope\":\"this-turn\",\"targetId\":\"a1\"");
        // 并显式禁止用 ADD 另加一条窄 scope 的约束（那样旧约束会继续生效）
        assertThat(prompt).contains("ADD 另加一条窄 scope 的约束");
    }

    @Test
    void sameValueRestatementIsTaughtAsNoopAndNeverNarrowed() {
        String prompt = ConstraintOpsPrompt.SYSTEM_PROMPT;

        assertThat(prompt).contains("\"op\":\"NOOP\"");
        assertThat(prompt).contains("不能收窄成 this-turn 或 current-topic");
        // 但不能把「换个话题」的同值要求也吞掉：本话题的约束不跨话题生效
        assertThat(prompt).contains("换个话题");
        // 旧措辞「或给同值的 ADD，但 scope 不得比原来更窄」是模型新增窄 scope 的直接诱因
        assertThat(prompt).doesNotContain("或给同值的 ADD");
    }

    @Test
    void additiveNarrowRequestIsStillTaughtAsAddSoTheWiderConstraintSurvives() {
        String prompt = ConstraintOpsPrompt.SYSTEM_PROMPT;

        // 没有把旧要求换掉时（「这一条回答只用中文」）仍是 ADD，两条并存
        assertThat(prompt).contains("\"op\":\"ADD\",\"slot\":\"language\",\"value\":\"中文\"");
        assertThat(prompt).contains("两条并存");
    }

    @Test
    void polarityExampleStaysInThePrompt() {
        // 带目标的否定要给出 FORBID + 被否定对象的槽位，示例格式不能丢
        assertThat(ConstraintOpsPrompt.SYSTEM_PROMPT)
                .contains("{\"slot\":\"table\",\"value\":\"format.table\",\"polarity\":\"forbid\"}");
    }

    @Test
    void promptExamplesFoldToTheExpectedActiveConstraints() {
        ConstraintOpMerger.Step english = new ConstraintOpMerger.Step("m1", 1, TOPIC, List.of(
                op(ConstraintOp.Op.ADD, "英文", ConstraintScope.CONVERSATION, null)));
        String englishId = merger.activeConstraints(List.of(english)).get(0).id();

        // 示例 1：窄化改口 → 只剩一条本话题的中文约束，会话级英文被撤回
        List<ConstraintOpMerger.Step> narrowed = List.of(english,
                new ConstraintOpMerger.Step("m2", 2, TOPIC, List.of(
                        op(ConstraintOp.Op.UPDATE, "中文", ConstraintScope.THIS_TURN, englishId))));
        assertThat(merger.activeConstraints(narrowed)).singleElement().satisfies(active -> {
            assertThat(active.slot()).isEqualTo(ConstraintSlot.LANGUAGE);
            assertThat(active.value()).isEqualTo("中文");
            assertThat(active.scope()).isEqualTo("CURRENT_TOPIC");
        });

        // 示例 3：同值重申的 NOOP 不改动任何已有约束，也不新增更窄的行
        List<ConstraintOpMerger.Step> restated = List.of(english,
                new ConstraintOpMerger.Step("m2", 2, TOPIC, List.of(
                        new ConstraintOp(ConstraintOp.Op.NOOP, null, null, null, null, HIGH))));
        assertThat(merger.activeConstraints(restated)).singleElement().satisfies(active -> {
            assertThat(active.value()).isEqualTo("英文");
            assertThat(active.scope()).isEqualTo("CONVERSATION");
        });
    }

    private static ConstraintOp op(ConstraintOp.Op kind, String value, ConstraintScope scope, String targetId) {
        return new ConstraintOp(kind, ConstraintSlot.LANGUAGE, value, scope, targetId, HIGH,
                ConstraintPolarity.REQUIRE);
    }
}
