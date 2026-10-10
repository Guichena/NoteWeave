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
        assertThat(constraints.get(0).scope()).isEqualTo("TECHNICAL_ANSWERS");
        assertThat(constraints.get(0).status()).isEqualTo("ACTIVE");
        assertThat(constraints.get(1).status()).isEqualTo("UNRESOLVED");
        assertThat(constraints.get(1).text()).isEmpty();
    }

    private TopicSegmenterV2.Message message(String id, int seq, String role, String text) {
        return new TopicSegmenterV2.Message(id, seq, role, text);
    }
}
