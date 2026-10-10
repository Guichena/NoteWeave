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

    private TopicSegmenterV2.Message message(String id, int seq, String role, String text) {
        return new TopicSegmenterV2.Message(id, seq, role, text);
    }
}
