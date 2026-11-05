package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 验收 11-04 中危 7：产物失败原因不能带契约、骨架、关键帧等内部用语。 */
class ArtifactFailureMessagesTest {

    @Test
    void missingVideoFramesBecomesActionableAdvice() {
        assertThat(ArtifactFailureMessages.forUser("该产物需要带关键帧的视频资料，所选资料中没有可用的视频关键帧"))
                .isEqualTo(ArtifactFailureMessages.NO_VIDEO_FRAMES)
                .doesNotContain("关键帧");
        assertThat(ArtifactFailureMessages.forUser("产物骨架缺少契约要求的关键帧"))
                .isEqualTo(ArtifactFailureMessages.NO_VIDEO_FRAMES);
    }

    @Test
    void contractAndSkeletonErrorsBecomeFormatCheckMessage() {
        assertThat(ArtifactFailureMessages.forUser("产物骨架数量不符合契约或用户选项"))
                .isEqualTo(ArtifactFailureMessages.FORMAT_CHECK);
        assertThat(ArtifactFailureMessages.forUser("删除失败单元后产物仍不满足交付契约：章节数量不符合契约"))
                .isEqualTo(ArtifactFailureMessages.FORMAT_CHECK);
    }

    @Test
    void timeoutAndRateLimitAreExplained() {
        assertThat(ArtifactFailureMessages.forUser("产物模型调用等待响应超时（180 秒）"))
                .isEqualTo(ArtifactFailureMessages.TIMEOUT);
        assertThat(ArtifactFailureMessages.forUser("LLM HTTP 429 Too Many Requests"))
                .isEqualTo(ArtifactFailureMessages.MODEL_BUSY);
    }

    @Test
    void otherInternalErrorsFallBackToGenericMessage() {
        assertThat(ArtifactFailureMessages.forUser("Traceback (most recent call last): KeyError 'sections'"))
                .isEqualTo(ArtifactFailureMessages.FALLBACK);
        assertThat(ArtifactFailureMessages.forUser("Skill payload invalid"))
                .isEqualTo(ArtifactFailureMessages.FALLBACK);
    }

    @Test
    void readableMessagesStayUnchangedAndCarryNoDetail() {
        String readable = "所选资料已被删除，请重新选择资料";
        assertThat(ArtifactFailureMessages.forUser(readable)).isEqualTo(readable);
        assertThat(ArtifactFailureMessages.detail(readable)).isNull();
        assertThat(ArtifactFailureMessages.forUser("  ")).isEmpty();
        assertThat(ArtifactFailureMessages.detail(null)).isNull();
    }

    @Test
    void rewrittenMessagesKeepTheOriginalAsDetail() {
        String raw = "产物骨架结构不合规";
        assertThat(ArtifactFailureMessages.detail(raw)).isEqualTo(raw);
    }
}
