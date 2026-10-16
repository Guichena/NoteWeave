package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TopicSegmentationMetricsTest {

    @Test
    void identicalLabelsScoreZero() {
        int[] labels = {0, 0, 0, 1, 1, 1};

        assertThat(TopicSegmentationMetrics.pk(labels, labels)).isZero();
        assertThat(TopicSegmentationMetrics.windowDiff(labels, labels)).isZero();
    }

    @Test
    void missedBoundaryOnShortSegmentsScoresOneFifth() {
        int[] reference = {0, 0, 0, 1, 1, 1};
        int[] hypothesis = {0, 0, 0, 0, 0, 0};

        assertThat(TopicSegmentationMetrics.pk(reference, hypothesis)).isEqualTo(1.0d / 5.0d);
        assertThat(TopicSegmentationMetrics.windowDiff(reference, hypothesis)).isEqualTo(1.0d / 5.0d);
    }

    @Test
    void missedBoundaryOnLongerSegmentsScoresTwoSixths() {
        int[] reference = {0, 0, 0, 0, 1, 1, 1, 1};
        int[] hypothesis = {0, 0, 0, 0, 0, 0, 0, 0};

        assertThat(TopicSegmentationMetrics.pk(reference, hypothesis)).isEqualTo(2.0d / 6.0d);
        assertThat(TopicSegmentationMetrics.windowDiff(reference, hypothesis)).isEqualTo(2.0d / 6.0d);
    }

    @Test
    void emptyOrUnequalLabelsAreRejected() {
        assertThatThrownBy(() -> TopicSegmentationMetrics.pk(new int[0], new int[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TopicSegmentationMetrics.windowDiff(new int[] {0, 1}, new int[] {0}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
