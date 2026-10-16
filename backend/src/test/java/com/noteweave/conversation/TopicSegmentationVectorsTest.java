package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.retrieval.provider.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopicSegmentationVectorsTest {

    @Test
    void standaloneQueryReplacesTheOriginalText() {
        CapturingClient client = new CapturingClient(List.of(List.of(1f, 0f)));
        List<TopicSegmenterV2.Message> attached = TopicSegmentationVectors.attach(List.of(
                new TopicSegmenterV2.Message("m1", 1, "USER", "原文")),
                Map.of("m1", "独立问题"), client);

        assertThat(client.inputs).containsExactly("独立问题");
        assertThat(attached.get(0).text()).isEqualTo("原文");
        assertThat(attached.get(0).vector()).containsExactly(1f, 0f);
    }

    @Test
    void blankStandaloneAndDisabledClientLeaveTheMessageUnchanged() {
        CapturingClient blank = new CapturingClient(List.of(List.of(0f, 1f)));
        List<TopicSegmenterV2.Message> messages = List.of(
                new TopicSegmenterV2.Message("m1", 1, "USER", "原文"),
                new TopicSegmenterV2.Message("a2", 2, "ASSISTANT", "好的。"));

        List<TopicSegmenterV2.Message> attached = TopicSegmentationVectors.attach(
                messages, Map.of("m1", "  "), blank);
        assertThat(blank.inputs).containsExactly("原文");
        assertThat(attached.get(1).vector()).isNull();

        CapturingClient disabled = new CapturingClient(List.of());
        disabled.enabled = false;
        assertThat(TopicSegmentationVectors.attach(messages, Map.of("m1", "独立问题"), disabled)
                .get(0).vector()).isNull();
        assertThat(disabled.inputs).isEmpty();
    }

    @Test
    void aShortVectorResultDiscardsEveryVector() {
        CapturingClient client = new CapturingClient(List.of());

        List<TopicSegmenterV2.Message> attached = TopicSegmentationVectors.attach(List.of(
                new TopicSegmenterV2.Message("m1", 1, "USER", "原文")), Map.of(), client);

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
