package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 改写模型输出的话题编号如何换成话题判断。 */
class TopicRoutingContextTest {

    private final ConversationTopicRoutingService.RoutingContext routing =
            new ConversationTopicRoutingService.RoutingContext(21,
                    List.of(new ConversationTopicRoutingService.RecentMessage(20, "ASSISTANT", "安平古堡")),
                    List.of(new ConversationTopicRoutingService.Candidate("T1", "topic-b", "台南旅行", "首问：去哪玩", 20),
                            new ConversationTopicRoutingService.Candidate("T2", "topic-a", "缓存一致性",
                                    "摘要：延迟双删与订阅 binlog", 12)),
                    "台南旅行摘要");

    @Test
    void firstTopicMeansContinueAndOlderTopicsMeanReturn() {
        assertThat(routing.resolve("T1")).isEqualTo(TopicRoute.continueCurrent());
        assertThat(routing.resolve("t2")).isEqualTo(TopicRoute.returnTo("topic-a"));
        assertThat(routing.resolve(" n ")).isEqualTo(TopicRoute.newTopic());
    }

    @Test
    void unknownOrEmptyTokensFallBackToRules() {
        assertThat(routing.resolve("T9")).isNull();
        assertThat(routing.resolve("")).isNull();
        assertThat(routing.resolve(null)).isNull();
        assertThat(ConversationTopicRoutingService.RoutingContext.EMPTY.resolve("T1")).isNull();
    }

    @Test
    void topicListMarksTheCurrentTopicAndCarriesGists() {
        assertThat(routing.renderTopics())
                .startsWith("T1（最近在聊）：台南旅行")
                .contains("T2：缓存一致性", "摘要：延迟双删与订阅 binlog");
        assertThat(routing.hasHistory()).isTrue();
    }
}
