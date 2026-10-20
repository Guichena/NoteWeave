package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationConstraintProjectorV2Test {
    private final ConversationConstraintProjectorV2 projector = new ConversationConstraintProjectorV2();

    @Test
    void explicitCorrectionRevokesOldLanguageRuleWithinTopic() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("n1", 1, "USER", "这份报告用英文。"),
                message("n2", 2, "ASSISTANT", "好的，我会用英文。"),
                message("n3", 3, "USER", "更正：不要英文，改用中文。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(3);
        assertThat(constraints.get(0).supersededBy()).isEqualTo(constraints.get(1).id());
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(1).text()).isEqualTo("中文");
    }

    @Test
    void crossTopicPreferenceIsExplicitAndVagueReferenceCannotBecomeActive() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("f1", 1, "USER", "以后解释技术问题先给结论。"),
                message("f2", 2, "USER", "解释索引选择。"),
                message("f3", 3, "USER", "现在解释 TCP 重传。"),
                message("f4", 4, "USER", "还是用那个格式。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).kind()).isEqualTo("FORMAT");
        // 带"以后"的要求在整个会话内生效，上下文规划时不受话题切换影响
        assertThat(constraints.get(0).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(0).text()).isEqualTo("解释技术问题先给结论");
        assertThat(constraints.get(0).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(1).status()).isEqualTo("UNRESOLVED");
        assertThat(constraints.get(1).text()).isEmpty();
    }

    @Test
    void commonPhrasingsOfFormatLanguageAndNegationAreRecognized() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("g1", 1, "USER", "介绍一下 Redis 的持久化方式，用表格对比 RDB 和 AOF。"),
                message("g2", 2, "USER", "每次回答都控制在300字以内。"),
                message("g3", 3, "USER", "接下来请用英文回答。"),
                message("g4", 4, "USER", "不要再贴大段配置文件了。"),
                message("g5", 5, "USER", "Redis 的过期键是怎么删除的？")));

        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::kind)
                .containsExactly("FORMAT", "FORMAT", "LANGUAGE", "NEGATION");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::text)
                .containsExactly("用表格对比 RDB 和 AOF", "回答都控制在300字以内", "英文", "不要再贴大段配置文件了");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::scope)
                .containsExactly("CURRENT_TOPIC", "CONVERSATION", "CURRENT_TOPIC", "CURRENT_TOPIC");
    }

    @Test
    void negatedLanguageIsNotTakenAsTheRequestedLanguage() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("h1", 1, "USER", "不要用英文，用中文写。")));

        assertThat(constraints).singleElement()
                .satisfies(constraint -> assertThat(constraint.text()).isEqualTo("中文"));
    }

    @Test
    void negatedLanguagePrefixesAreNotTakenAsTheRequestedLanguage() {
        // 否定前缀不能只有"不要/别/不"："不是用英文""禁止使用英文"同样是在拒绝英文
        for (String text : List.of("不是用英文。", "不要用英文。", "不用英文。", "禁止使用英文。",
                "避免用英文回答。", "别用英文。", "不要再用英文。", "不要总是用英文。")) {
            List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                    message("x1", 1, "USER", text)));

            assertThat(constraints).as(text).singleElement().satisfies(constraint -> {
                assertThat(constraint.kind()).as(text).isEqualTo("NEGATION");
                assertThat(constraint.status()).isEqualTo("ACTIVE");
                assertThat(constraint.text()).as(text).isEqualTo(text.substring(0, text.length() - 1));
            });
        }
    }

    @Test
    void negationBetweenPrefixAndCueStillLeavesTheRequestPositive() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("w1", 1, "USER", "不要忘记用英文。")));

        assertThat(constraints).singleElement().satisfies(constraint -> {
            assertThat(constraint.kind()).isEqualTo("LANGUAGE");
            assertThat(constraint.text()).isEqualTo("英文");
        });
    }

    @Test
    void negatedLanguageInOneClauseDoesNotCancelPositiveLanguageInAnother() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("y1", 1, "USER", "不是用英文，用中文写。")));

        assertThat(constraints).singleElement().satisfies(constraint -> {
            assertThat(constraint.kind()).isEqualTo("LANGUAGE");
            assertThat(constraint.text()).isEqualTo("中文");
        });
    }

    @Test
    void negationTakesPrecedenceOverFormatCueInTheSameClause() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("z1", 1, "USER", "不要用表格。"),
                message("z2", 2, "USER", "禁止使用表格对比。")));

        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::kind)
                .containsExactly("NEGATION", "NEGATION");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::text)
                .containsExactly("不要用表格", "禁止使用表格对比");
    }

    @Test
    void laterValueOfSameKindRevokesEarlierOneWithoutCorrectionKeyword() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("k1", 1, "USER", "这份报告用英文。"),
                message("k2", 2, "ASSISTANT", "好的，我会用英文。"),
                message("k3", 3, "USER", "用中文。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).kind()).isEqualTo("LANGUAGE");
        assertThat(constraints.get(0).scope()).isEqualTo("CURRENT_TOPIC");
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(3);
        assertThat(constraints.get(0).supersededBy()).isEqualTo(constraints.get(1).id());
        assertThat(constraints.get(1).kind()).isEqualTo("LANGUAGE");
        assertThat(constraints.get(1).text()).isEqualTo("中文");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(1).invalidAfterSeq()).isNull();
        assertThat(constraints.get(1).supersededBy()).isNull();
    }

    @Test
    void newConversationWideFormatPreferenceSupersedesEarlierOne() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("p1", 1, "USER", "以后解释技术问题先给结论。"),
                message("p2", 2, "USER", "以后用表格对比。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints.get(0).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(0).text()).isEqualTo("解释技术问题先给结论");
        assertThat(constraints.get(0).status()).isEqualTo("REVOKED");
        assertThat(constraints.get(0).invalidAfterSeq()).isEqualTo(2);
        assertThat(constraints.get(1).scope()).isEqualTo("CONVERSATION");
        assertThat(constraints.get(1).text()).isEqualTo("用表格对比");
        assertThat(constraints.get(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void constraintsOfDifferentKindOrScopeInTheSameTopicAllStayActive() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("d1", 1, "USER", "每次回答都控制在300字以内。"),
                message("d2", 2, "USER", "不要再贴大段配置文件了。"),
                message("d3", 3, "USER", "先给结论。")));

        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::kind)
                .containsExactly("FORMAT", "NEGATION", "FORMAT");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::scope)
                .containsExactly("CONVERSATION", "CURRENT_TOPIC", "CURRENT_TOPIC");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::status)
                .containsOnly("ACTIVE");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::invalidAfterSeq)
                .containsOnlyNulls();
    }

    @Test
    void sameKindInDifferentTopicsIsNotRevoked() {
        List<ConversationConstraintProjectorV2.Constraint> constraints = projector.project(List.of(
                message("t1", 1, "USER", "用表格对比 RDB 和 AOF 的持久化差异。"),
                message("t2", 2, "USER", "换个话题，用表格列出 Redis 过期键的删除策略。")));

        assertThat(constraints).hasSize(2);
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::scope)
                .containsExactly("CURRENT_TOPIC", "CURRENT_TOPIC");
        assertThat(constraints).extracting(ConversationConstraintProjectorV2.Constraint::status)
                .containsOnly("ACTIVE");
        assertThat(constraints.get(1).invalidAfterSeq()).isNull();
    }

    @Test
    void loneConstraintIsNotSupersededByItselfAndReprojectionIsStable() {
        List<TopicSegmenterV2.Message> messages = List.of(message("s1", 1, "USER", "这份报告用英文。"));

        List<ConversationConstraintProjectorV2.Constraint> first = projector.project(messages);
        assertThat(first).singleElement().satisfies(constraint -> {
            assertThat(constraint.status()).isEqualTo("ACTIVE");
            assertThat(constraint.invalidAfterSeq()).isNull();
            assertThat(constraint.supersededBy()).isNull();
        });
        // 投影会随每一轮重算，同样的输入必须给出同样的结果
        assertThat(projector.project(messages)).isEqualTo(first);
    }

    private TopicSegmenterV2.Message message(String id, int seq, String role, String text) {
        return new TopicSegmenterV2.Message(id, seq, role, text);
    }
}
