package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BufferedAnswerDeltaEmitterTest {
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
