package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BufferedAnswerDeltaEmitterTest {
    @Test
    void schedulerAndProducerMustDeliverInDrainOrder() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.ExecutorService producer = Executors.newSingleThreadExecutor();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        java.util.List<String> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
        String second = "B".repeat(512);
        BufferedAnswerDeltaEmitter emitter = new BufferedAnswerDeltaEmitter(scheduler, batch -> {
            if (batch.equals("A")) {
                firstEntered.countDown();
                try {
                    if (!releaseFirst.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("首批发送等待超时");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            } else {
                secondEntered.countDown();
            }
            delivered.add(batch);
        });
        try {
            emitter.accept("A");
            org.assertj.core.api.Assertions.assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<?> next = producer.submit(() -> emitter.accept(second));
            // 首批发送受屏障阻挡时，后续生产线程不得越过它发送正文。
            org.assertj.core.api.Assertions.assertThat(secondEntered.await(150, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            next.get(2, TimeUnit.SECONDS);
            emitter.flush();
            org.assertj.core.api.Assertions.assertThat(delivered).containsExactly("A", second);
        } finally {
            releaseFirst.countDown();
            producer.shutdownNow();
            scheduler.shutdownNow();
            producer.awaitTermination(2, TimeUnit.SECONDS);
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
            emitter.close();
        }
    }

    @Test
    void closeDoesNotSelfSuppressFailureAlreadyReportedByFlush() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        RuntimeException original = new RuntimeException("original sink failure");
        CountDownLatch sinkCalled = new CountDownLatch(1);
        try {
            BufferedAnswerDeltaEmitter emitter = new BufferedAnswerDeltaEmitter(scheduler, ignored -> {
                sinkCalled.countDown();
                throw original;
            });
            emitter.accept("token");
            org.assertj.core.api.Assertions.assertThat(sinkCalled.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> {
                try (emitter) {
                    emitter.flush();
                }
            }).isSameAs(original);
        } finally {
            scheduler.shutdownNow();
        }
    }
}
