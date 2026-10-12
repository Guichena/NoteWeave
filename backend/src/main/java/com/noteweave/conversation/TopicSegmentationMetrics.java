package com.noteweave.conversation;

/**
 * 话题切分的 Pk 和 WindowDiff。窗口宽度取参考分段平均长度的一半，至少为 1。
 * 两个指标都只比较长度为 k 的窗口，不依赖未来消息的标注格式以外的信息。
 */
public final class TopicSegmentationMetrics {
    private TopicSegmentationMetrics() {
    }

    public static double pk(int[] reference, int[] hypothesis) {
        return disagreement(reference, hypothesis, false);
    }

    public static double windowDiff(int[] reference, int[] hypothesis) {
        return disagreement(reference, hypothesis, true);
    }

    private static double disagreement(int[] reference, int[] hypothesis, boolean countBoundaries) {
        if (reference == null || hypothesis == null || reference.length == 0 || hypothesis.length == 0
                || reference.length != hypothesis.length) {
            throw new IllegalArgumentException("segmentation labels must be non-empty and the same length");
        }
        int window = windowSize(reference);
        int comparisons = reference.length - window;
        if (comparisons <= 0) {
            return 0.0d;
        }
        int errors = 0;
        for (int start = 0; start < comparisons; start++) {
            int end = start + window;
            boolean mismatch = countBoundaries
                    ? boundaries(reference, start, end) != boundaries(hypothesis, start, end)
                    : (reference[start] == reference[end]) != (hypothesis[start] == hypothesis[end]);
            if (mismatch) {
                errors++;
            }
        }
        return (double) errors / comparisons;
    }

    static int windowSize(int[] reference) {
        int segments = 1;
        for (int index = 1; index < reference.length; index++) {
            if (reference[index] != reference[index - 1]) {
                segments++;
            }
        }
        int average = reference.length / segments;
        return Math.max(1, average / 2);
    }

    private static int boundaries(int[] labels, int start, int end) {
        int count = 0;
        for (int index = start + 1; index <= end; index++) {
            if (labels[index] != labels[index - 1]) {
                count++;
            }
        }
        return count;
    }
}
