package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SourceEmbeddingHeartbeatTest {
    @Test
    void renewsWhileCallerWaitsAndStopsOnClose() throws Exception {
        try (var heartbeat = new SourceEmbeddingHeartbeat()) {
            CountDownLatch renewed = new CountDownLatch(3);
            AtomicInteger calls = new AtomicInteger();
            var lease = heartbeat.start(() -> { calls.incrementAndGet(); renewed.countDown(); }, 10);
            assertThat(renewed.await(3, TimeUnit.SECONDS)).isTrue();
            lease.check();
            lease.close();
            // 等待一个后续调度点，确认取消的任务已从调度器移除。
            CountDownLatch next = new CountDownLatch(1);
            try (var another = heartbeat.start(next::countDown, 30)) {
                int stopped = calls.get();
                assertThat(next.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(calls.get()).isEqualTo(stopped);
            }
        }
    }

    @Test
    void renewalFailureAndShutdownRejectFurtherWork() throws Exception {
        try (var heartbeat = new SourceEmbeddingHeartbeat()) {
            CountDownLatch attempted = new CountDownLatch(1);
            var lease = heartbeat.start(() -> {
                attempted.countDown();
                throw new IllegalStateException("lost");
            }, 10);
            assertThat(attempted.await(3, TimeUnit.SECONDS)).isTrue();
            CountDownLatch next = new CountDownLatch(1);
            try (var another = heartbeat.start(next::countDown, 30)) {
                assertThat(next.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(lease::check).hasMessageContaining("LEASE_LOST");
            }
            heartbeat.close();
            assertThatThrownBy(lease::check).hasMessageContaining("LEASE_LOST");
            assertThatThrownBy(() -> heartbeat.start(() -> {}, 10)).hasMessageContaining("CLOSED");
        }
    }
}
