package com.noteweave.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 秒传复用策略的纯单元测试：不启动 Spring 上下文，只验证复用判定的组合。 */
class UploadInstantSourceReusePolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void succeededSourceIsReusedRegardlessOfIdleTime() {
        assertThat(UploadService.reusableInstantSource(
                "READY", "PARSED", NOW.minus(Duration.ofDays(30)), false, NOW)).isTrue();
        assertThat(UploadService.reusableInstantSource(
                "READY", "PARSED", NOW.minus(Duration.ofDays(30)), true, NOW)).isTrue();
    }

    @Test
    void processingSourceWithLiveParseTaskAndRecentProgressIsReused() {
        assertThat(UploadService.reusableInstantSource(
                "PROCESSING", "PARSING", NOW.minus(Duration.ofMinutes(5)), true, NOW)).isTrue();
        assertThat(UploadService.reusableInstantSource(
                "PROCESSING", "PARSED", NOW.minus(Duration.ofMinutes(29)), true, NOW)).isTrue();
    }

    @Test
    void processingSourceWithoutActiveParseTaskIsNotReused() {
        assertThat(UploadService.reusableInstantSource(
                "PROCESSING", "PARSING", NOW.minus(Duration.ofMinutes(1)), false, NOW)).isFalse();
    }

    @Test
    void stalledProcessingSourceBeyondWindowIsNotReused() {
        Instant stalled = NOW.minus(UploadService.INSTANT_REUSE_PROGRESS_WINDOW).minusSeconds(1);
        assertThat(UploadService.reusableInstantSource("PROCESSING", "PARSING", stalled, true, NOW)).isFalse();
        assertThat(UploadService.reusableInstantSource("PROCESSING", "PARSED", stalled, true, NOW)).isFalse();
    }

    @Test
    void windowBoundaryIsExclusive() {
        Instant boundary = NOW.minus(UploadService.INSTANT_REUSE_PROGRESS_WINDOW);
        assertThat(UploadService.reusableInstantSource("PROCESSING", "PARSING", boundary, true, NOW)).isFalse();
    }

    @Test
    void readySourceWithoutParsedStateIsNotTreatedAsSucceeded() {
        assertThat(UploadService.reusableInstantSource("READY", "FAILED", NOW, false, NOW)).isFalse();
    }

    @Test
    void missingSourceUpdatedAtIsNotReused() {
        assertThat(UploadService.reusableInstantSource("PROCESSING", "PARSING", null, true, NOW)).isFalse();
    }
}
