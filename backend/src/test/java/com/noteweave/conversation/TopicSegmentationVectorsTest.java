package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopicSegmentationVectorsTest {

    /** M5-M08：切分向量只吃用户原话，检索改写出的 standalone query 不得进入 embedding。 */
    @Test
    void embedsTheOriginalUserTextAndNeverARewrittenQuery() {
        CapturingClient client = new CapturingClient(List.of(List.of(1f, 0f), List.of(0f, 1f)));

        List<TopicSegmenterV2.Message> attached = TopicSegmentationVectors.attach(List.of(
                new TopicSegmenterV2.Message("m1", 1, "USER", "它为什么重要"),
                new TopicSegmenterV2.Message("a2", 2, "ASSISTANT", "因为它决定了写入顺序。"),
                new TopicSegmenterV2.Message("m3", 3, "USER", "那怎么落地")), client);

        assertThat(client.inputs).containsExactly("它为什么重要", "那怎么落地");
        assertThat(attached.get(0).text()).isEqualTo("它为什么重要");
        assertThat(attached.get(0).vector()).containsExactly(1f, 0f);
        assertThat(attached.get(1).vector()).isNull();
        assertThat(attached.get(2).vector()).containsExactly(0f, 1f);
    }

    @Test
    void blankUserTextAndDisabledClientLeaveTheMessageUnchanged() {
        CapturingClient blank = new CapturingClient(List.of(List.of(0f, 1f)));
        List<TopicSegmenterV2.Message> messages = List.of(
                new TopicSegmenterV2.Message("m1", 1, "USER", "原文"),
                new TopicSegmenterV2.Message("m2", 2, "USER", "   "),
                new TopicSegmenterV2.Message("a3", 3, "ASSISTANT", "好的。"));

        List<TopicSegmenterV2.Message> attached = TopicSegmentationVectors.attach(messages, blank);
        assertThat(blank.inputs).containsExactly("原文");
        assertThat(attached.get(1).vector()).isNull();
        assertThat(attached.get(2).vector()).isNull();

        CapturingClient disabled = new CapturingClient(List.of());
        disabled.enabled = false;
        assertThat(TopicSegmentationVectors.attach(messages, disabled).get(0).vector()).isNull();
        assertThat(disabled.inputs).isEmpty();

        assertThat(TopicSegmentationVectors.attach(messages, null)).isSameAs(messages);
    }

    @Test
    void aShortVectorResultDiscardsEveryVector() {
        CapturingClient client = new CapturingClient(List.of());

        List<TopicSegmenterV2.Message> attached = TopicSegmentationVectors.attach(List.of(
                new TopicSegmenterV2.Message("m1", 1, "USER", "原文")), client);

        assertThat(client.inputs).containsExactly("原文");
        assertThat(attached.get(0).vector()).isNull();
    }

    private static final class CapturingClient implements EmbeddingClient {
        private final List<List<Float>> vectors;
        private final List<String> inputs = new ArrayList<>();
        private boolean enabled = true;

        private CapturingClient(List<List<Float>> vectors) {
            this.vectors = vectors;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public EmbeddingResult embedQuery(String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmbeddingResult embedDocuments(List<String> texts) {
            inputs.addAll(texts);
            return new EmbeddingResult(vectors, "test", 2, 0);
        }
    }
}
