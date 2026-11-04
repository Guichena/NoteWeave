package com.noteweave.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 链接预览失败时把 Worker 给出的上游原因交给用户，缺失时退回通用文案。 */
class SourceLinkWorkerClientPreviewErrorTest {

    @Test
    void upstreamDetailFromWorkerBodyIsShownToTheUser() {
        String body = "{\"detail\":\"Error executing tool get_video_info: B 站拒绝了当前服务器出口的访问"
                + "（HTTP 412 风控），需要配置 B 站 Cookie 或代理\"}";

        String message = SourceLinkWorkerClient.previewFailureMessage(body);

        assertThat(message).startsWith("无法读取 B 站视频信息：")
                .contains("HTTP 412 风控")
                .contains("Cookie 或代理")
                .doesNotContain("{", "\"detail\"");
    }

    @Test
    void blankOrUnparsableBodyFallsBackToTheGenericHint() {
        assertThat(SourceLinkWorkerClient.previewFailureMessage(null))
                .isEqualTo("无法读取 B 站视频信息，请检查链接后重试");
        assertThat(SourceLinkWorkerClient.previewFailureMessage("   "))
                .isEqualTo("无法读取 B 站视频信息，请检查链接后重试");
        assertThat(SourceLinkWorkerClient.previewFailureMessage("{\"detail\":\"\"}"))
                .isEqualTo("无法读取 B 站视频信息，请检查链接后重试");
        assertThat(SourceLinkWorkerClient.previewFailureMessage("upstream offline"))
                .isEqualTo("无法读取 B 站视频信息：upstream offline");
    }

    @Test
    void overlyLongDetailIsClipped() {
        String body = "{\"detail\":\"" + "x".repeat(400) + "\"}";

        String message = SourceLinkWorkerClient.previewFailureMessage(body);

        assertThat(message).endsWith("…");
        assertThat(message.length()).isLessThan(400);
    }
}
