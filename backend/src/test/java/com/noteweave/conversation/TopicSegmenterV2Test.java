package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopicSegmenterV2Test {
    private final TopicSegmenterV2 segmenter = new TopicSegmenterV2();

    @Test
    void returningToTopicAReusesItsIdentityWithoutCoveringTopicB() throws Exception {
        JsonNode gold = goldCase("A_TO_B_TO_A");
        TopicSegmenterV2.Projection projection = segmenter.segment(messages(gold));

        assertThat(projection.segments()).hasSize(3);
        assertThat(projection.segments()).extracting(TopicSegmenterV2.Segment::startSeq)
                .containsExactly(1, 3, 11);
        assertThat(projection.segments()).extracting(TopicSegmenterV2.Segment::endSeq)
                .containsExactly(2, 10, 11);
        assertThat(projection.segments().get(0).topicId())
                .isEqualTo(projection.segments().get(2).topicId())
                .isNotEqualTo(projection.segments().get(1).topicId());
        assertThat(projection.segments().get(2).reason()).isEqualTo("EXPLICIT_TOPIC_RETURN");
    }

    @Test
    void similarWordsWithoutAnAnchorAreUncertainInsteadOfBorrowingOldTopic() throws Exception {
        TopicSegmenterV2.Projection projection = segmenter.segment(
                messages(goldCase("SIMILAR_ENTITIES_DISTINCT_TOPIC")));

        assertThat(projection.segments()).hasSize(2);
        assertThat(projection.segments().get(0).topicId())
                .isNotEqualTo(projection.segments().get(1).topicId());
        assertThat(projection.segments().get(1).status()).isEqualTo("UNCERTAIN");
        assertThat(projection.segments().get(1).reason()).isEqualTo("SHORT_FOLLOW_UP");
    }

    @Test
    void correctionsStayWithActiveTopicAndAssistantCannotCreateNewTopic() {
        TopicSegmenterV2.Projection projection = segmenter.segment(List.of(
                new TopicSegmenterV2.Message("n1", 1, "USER", "这份报告用英文。"),
                new TopicSegmenterV2.Message("n2", 2, "ASSISTANT", "好的。"),
                new TopicSegmenterV2.Message("n3", 3, "USER", "更正：不要英文，改用中文。")));

        assertThat(projection.segments()).hasSize(1);
        assertThat(projection.segments().get(0).endSeq()).isEqualTo(3);
    }

    @Test
    void ambiguousReturnDoesNotClaimAnUnrelatedOldTopic() {
        TopicSegmenterV2.Projection projection = segmenter.segment(List.of(
                new TopicSegmenterV2.Message("a1", 1, "USER", "解释缓存一致性。"),
                new TopicSegmenterV2.Message("b2", 2, "USER", "改聊台南旅行。"),
                new TopicSegmenterV2.Message("u3", 3, "USER", "回到刚才那个问题。")));

        assertThat(projection.segments()).hasSize(3);
        assertThat(projection.segments().get(2).status()).isEqualTo("UNCERTAIN");
        assertThat(projection.segments().get(2).topicId())
                .isNotEqualTo(projection.segments().get(0).topicId());
    }

    @Test
    void duplicateOrOutOfOrderSequenceIsRejected() {
        assertThatThrownBy(() -> segmenter.segment(List.of(
                new TopicSegmenterV2.Message("one", 1, "USER", "A"),
                new TopicSegmenterV2.Message("two", 1, "USER", "B"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JsonNode goldCase(String scenario) throws Exception {
        try (var input = getClass().getResourceAsStream("/conversation/context-v2-gold-v1.json")) {
            for (JsonNode entry : new ObjectMapper().readTree(input).path("cases")) {
                if (scenario.equals(entry.path("scenario").asText())) return entry;
            }
        }
        throw new IllegalArgumentException("unknown gold scenario: " + scenario);
    }

    private List<TopicSegmenterV2.Message> messages(JsonNode entry) {
        List<TopicSegmenterV2.Message> messages = new ArrayList<>();
        for (JsonNode message : entry.path("messages")) {
            messages.add(new TopicSegmenterV2.Message(message.path("id").asText(),
                    message.path("seq").asInt(), "USER", message.path("text").asText()));
        }
        return messages;
    }
}
