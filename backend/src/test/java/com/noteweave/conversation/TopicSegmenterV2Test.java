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
    void similarQuestionReturnsToEarlierTopicWithoutAnExplicitCue() {
        TopicSegmenterV2.Projection projection = segmenter.segment(List.of(
                new TopicSegmenterV2.Message("c1", 1, "USER", "缓存一致性有哪些方案，先更新数据库还是先删除缓存？"),
                new TopicSegmenterV2.Message("c2", 2, "ASSISTANT", "推荐先更新数据库再删除缓存，删除失败时用消息队列重试。"),
                new TopicSegmenterV2.Message("k3", 3, "USER", "Kafka 消费者组的分区再均衡是怎么触发的，会不会丢消息？"),
                new TopicSegmenterV2.Message("k4", 4, "ASSISTANT", "消费者加入或离开消费者组时触发分区再均衡。"),
                new TopicSegmenterV2.Message("c5", 5, "USER", "删除缓存失败以后，消息队列重试和订阅 binlog 哪个更可靠？")));

        assertThat(projection.segments()).hasSize(3);
        assertThat(projection.segments().get(1).startSeq()).isEqualTo(3);
        assertThat(projection.segments().get(1).status()).isEqualTo("UNCERTAIN");
        assertThat(projection.segments().get(2).topicId()).isEqualTo(projection.segments().get(0).topicId());
        assertThat(projection.segments().get(2).reason()).isEqualTo("PRIOR_TOPIC_SIMILAR");
    }

    @Test
    void anOffTopicAssistantAnswerDoesNotPullTheNextQuestionIntoItsTopic() {
        String cacheAnswer = "推荐先更新数据库再删除缓存。删除缓存失败时把删除操作写入消息队列重试，"
                + "同时订阅 binlog 再删一次缓存兜底；删除缓存、消息队列重试和 binlog 订阅可以组合使用。";
        TopicSegmenterV2.Projection projection = segmenter.segment(List.of(
                new TopicSegmenterV2.Message("c1", 1, "USER", "缓存一致性有哪些方案，先更新数据库还是先删除缓存？"),
                new TopicSegmenterV2.Message("c2", 2, "ASSISTANT", cacheAnswer),
                new TopicSegmenterV2.Message("k3", 3, "USER", "Kafka 消费者组的分区再均衡是怎么触发的，会不会丢消息？"),
                // 资料里没有 Kafka 的内容，回答又绕回了缓存
                new TopicSegmenterV2.Message("k4", 4, "ASSISTANT", cacheAnswer),
                new TopicSegmenterV2.Message("c5", 5, "USER", "删除缓存失败以后，消息队列重试和订阅 binlog 哪个更可靠？")));

        assertThat(projection.segments()).hasSize(3);
        assertThat(projection.segments().get(2).startSeq()).isEqualTo(5);
        assertThat(projection.segments().get(2).topicId()).isEqualTo(projection.segments().get(0).topicId());
    }

    @Test
    void oneSharedCommonWordDoesNotJoinUnrelatedTopics() {
        TopicSegmenterV2.Projection projection = segmenter.segment(List.of(
                new TopicSegmenterV2.Message("a1", 1, "USER", "解释一下数据库索引的最左前缀匹配原则"),
                new TopicSegmenterV2.Message("a2", 2, "ASSISTANT", "联合索引按照列的顺序建立 B+ 树，查询条件必须从最左边的列开始匹配。"),
                new TopicSegmenterV2.Message("a3", 3, "USER", "联合索引的最左前缀匹配在范围查询时会失效吗"),
                new TopicSegmenterV2.Message("b4", 4, "USER", "解释一下 TCP 三次握手和四次挥手的过程")));

        // 第 3 条与索引话题共享"联合索引""最左前缀"等词，归入原话题；第 4 条只和前文共享"解释一下"，开启新话题
        assertThat(projection.segments()).hasSize(2);
        assertThat(projection.segments().get(0).endSeq()).isEqualTo(3);
        assertThat(projection.segments().get(1).startSeq()).isEqualTo(4);
        assertThat(projection.segments().get(1).topicId()).isNotEqualTo(projection.segments().get(0).topicId());
    }

    @Test
    void persistedSegmentsAreKeptAsAFixedPrefix() {
        List<TopicSegmenterV2.Message> messages = List.of(
                new TopicSegmenterV2.Message("m1", 1, "USER", "解释缓存一致性。"),
                new TopicSegmenterV2.Message("m2", 2, "USER", "Kafka 消费者组的分区再均衡是怎么触发的？"),
                new TopicSegmenterV2.Message("m3", 3, "USER", "那删除失败呢？"));
        // 旧规则把前两条划成了同一个话题；新规则不回溯修改，只对第 3 条做判断
        TopicSegmenterV2.Segment stored = new TopicSegmenterV2.Segment("segment-old", "topic-old", 1, 2,
                "CONFIDENT", "SHORT_FOLLOW_UP", "topic-segmenter-v2-a1");

        TopicSegmenterV2.Projection projection = segmenter.segment(messages, List.of(stored));

        assertThat(projection.segments()).hasSize(1);
        assertThat(projection.segments().get(0).segmentId()).isEqualTo("segment-old");
        assertThat(projection.segments().get(0).endSeq()).isEqualTo(3);
        assertThat(projection.segments().get(0).ruleVersion()).isEqualTo("topic-segmenter-v2-a1");
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
