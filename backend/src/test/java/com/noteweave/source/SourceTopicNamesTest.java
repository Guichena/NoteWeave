package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.config.NoteWeaveProperties;
import org.junit.jupiter.api.Test;

/** 主题名只从配置读取：默认值与历史常量一致，改配置后所有分支都跟着改。 */
class SourceTopicNamesTest {

    @Test
    void defaultConfigurationShouldMatchPipelineStageConstants() {
        SourceTopicNames topics = SourceTopicNamesFixture.defaults();

        assertThat(topics.parse()).isEqualTo(SourcePipelineStages.TOPIC_PARSE);
        assertThat(topics.chunk()).isEqualTo(SourcePipelineStages.TOPIC_CHUNK);
        assertThat(topics.embed()).isEqualTo(SourcePipelineStages.TOPIC_EMBED);
        assertThat(topics.index()).isEqualTo(SourcePipelineStages.TOPIC_INDEX);
        assertThat(topics.retrievalProjection()).isEqualTo("noteweave.retrieval.projection");
    }

    @Test
    void configuredTopicsShouldOverrideEveryDefault() {
        SourceTopicNames topics = SourceTopicNamesFixture.custom(
                "acme.parse", "acme.chunk", "acme.embed", "acme.index", "acme.projection");

        assertThat(topics.parse()).isEqualTo("acme.parse");
        assertThat(topics.chunk()).isEqualTo("acme.chunk");
        assertThat(topics.embed()).isEqualTo("acme.embed");
        assertThat(topics.index()).isEqualTo("acme.index");
        assertThat(topics.retrievalProjection()).isEqualTo("acme.projection");
    }

    @Test
    void stageClassificationShouldFollowConfiguredNamesOnly() {
        SourceTopicNames topics = SourceTopicNamesFixture.custom(
                "acme.parse", "acme.chunk", "acme.embed", "acme.index", "acme.projection");

        assertThat(topics.isParseStage("acme.parse")).isTrue();
        assertThat(topics.isParseStage("acme.chunk")).isTrue();
        assertThat(topics.isParseStage(SourcePipelineStages.TOPIC_PARSE)).isFalse();
        assertThat(topics.isParseStage(SourcePipelineStages.TOPIC_CHUNK)).isFalse();
        assertThat(topics.isParseStage("acme.wiki.ingest")).isFalse();
        assertThat(topics.isParseStage(null)).isFalse();

        assertThat(topics.isRetrievalProjection("acme.embed")).isTrue();
        assertThat(topics.isRetrievalProjection("acme.index")).isTrue();
        assertThat(topics.isRetrievalProjection("acme.projection")).isTrue();
        assertThat(topics.isRetrievalProjection(SourcePipelineStages.TOPIC_EMBED)).isFalse();
        assertThat(topics.isRetrievalProjection(SourcePipelineStages.TOPIC_INDEX)).isFalse();
        assertThat(topics.isRetrievalProjection("acme.parse")).isFalse();
        assertThat(topics.isRetrievalProjection(null)).isFalse();
    }

    /** 配置缺项时退回历史缺省主题名，不能让比较分支拿到 null 而抛空指针。 */
    @Test
    void blankConfigurationShouldFallBackToDefaultTopicNames() {
        NoteWeaveProperties properties = new NoteWeaveProperties(null, null, null,
                new NoteWeaveProperties.Kafka(true, new NoteWeaveProperties.Topics(
                        " ", null, "custom.wiki.ingest", "custom.wiki.retract",
                        "custom.conversation.summary", "custom.memory.extraction", " ", null, "")),
                null, null);

        SourceTopicNames topics = new SourceTopicNames(properties);

        assertThat(topics.parse()).isEqualTo(SourcePipelineStages.TOPIC_PARSE);
        assertThat(topics.chunk()).isEqualTo(SourcePipelineStages.TOPIC_CHUNK);
        assertThat(topics.embed()).isEqualTo(SourcePipelineStages.TOPIC_EMBED);
        assertThat(topics.index()).isEqualTo(SourcePipelineStages.TOPIC_INDEX);
        assertThat(topics.retrievalProjection()).isEqualTo("noteweave.retrieval.projection");
    }
}
