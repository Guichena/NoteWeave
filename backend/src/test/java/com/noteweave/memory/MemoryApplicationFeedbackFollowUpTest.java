package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 跟进反馈只按模型结论分支：代码里没有任何词表，也没有地方再看消息正文。
 * 模型没给出结论时本轮跳过并标可重试，不再把沉默或中性记成接受。
 */
class MemoryApplicationFeedbackFollowUpTest {

    @Test
    void positiveFeedbackWritesAcceptance() {
        assertThat(MemoryApplicationFeedbackService.followUpResult(
                judgment(true, MemoryFeedbackAspectJudge.POLARITY_POSITIVE, "UNKNOWN")))
                .isEqualTo(MemoryApplicationFeedbackService.FollowUpResult.ACCEPTED);
    }

    @Test
    void negativeFeedbackWritesTheNegativeSignal() {
        assertThat(MemoryApplicationFeedbackService.followUpResult(
                judgment(true, MemoryFeedbackAspectJudge.POLARITY_NEGATIVE, "LENGTH")))
                .isEqualTo(MemoryApplicationFeedbackService.FollowUpResult.NEGATIVE);
    }

    @Test
    void neutralFeedbackWritesNothing() {
        // 旧实现把「不是不满」一律记成 ACCEPTED，沉默会累积成正向
        assertThat(MemoryApplicationFeedbackService.followUpResult(
                judgment(true, MemoryFeedbackAspectJudge.POLARITY_NEUTRAL, "UNKNOWN")))
                .isEqualTo(MemoryApplicationFeedbackService.FollowUpResult.SKIPPED);
    }

    @Test
    void messageThatIsNotFeedbackOnThePreviousAnswerWritesNothing() {
        assertThat(MemoryApplicationFeedbackService.followUpResult(
                judgment(false, MemoryFeedbackAspectJudge.POLARITY_POSITIVE, "UNKNOWN")))
                .isEqualTo(MemoryApplicationFeedbackService.FollowUpResult.SKIPPED);
        assertThat(MemoryApplicationFeedbackService.followUpResult(
                judgment(false, MemoryFeedbackAspectJudge.POLARITY_NEUTRAL, "UNKNOWN")))
                .isEqualTo(MemoryApplicationFeedbackService.FollowUpResult.SKIPPED);
    }

    @Test
    void modelFailureSkipsThisRoundAndIsRetryable() {
        MemoryFeedbackAspectJudge.Judgment notJudged = new MemoryFeedbackAspectJudge.Judgment(
                false, MemoryFeedbackAspectJudge.POLARITY_NEUTRAL, MemoryFeedbackAspectJudge.ASPECT_UNKNOWN,
                MemoryFeedbackAspectJudge.ASPECT_NOT_RUN);

        assertThat(MemoryApplicationFeedbackService.followUpResult(notJudged))
                .isEqualTo(MemoryApplicationFeedbackService.FollowUpResult.RETRYABLE);
    }

    /**
     * 分支只看模型给的三个字段：{@code followUpResult} 的入参里没有消息正文，
     * 代码没有任何词表可以参与判断（旧实现按「太长/详细」等词表定方面、按「有没有不满」定接受）。
     */
    @Test
    void wordingNeverDecidesTheOutcome() {
        assertThat(MemoryApplicationFeedbackService.followUpResult(
                judgment(true, MemoryFeedbackAspectJudge.POLARITY_POSITIVE, "UNKNOWN")))
                .isEqualTo(MemoryApplicationFeedbackService.FollowUpResult.ACCEPTED);
        assertThat(MemoryApplicationFeedbackService.followUpResult(
                judgment(true, MemoryFeedbackAspectJudge.POLARITY_NEGATIVE, "UNKNOWN")))
                .isEqualTo(MemoryApplicationFeedbackService.FollowUpResult.NEGATIVE);
    }

    private static MemoryFeedbackAspectJudge.Judgment judgment(boolean feedback, String polarity, String aspect) {
        return new MemoryFeedbackAspectJudge.Judgment(feedback, polarity, aspect, null);
    }
}
