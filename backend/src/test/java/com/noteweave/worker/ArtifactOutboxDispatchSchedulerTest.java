package com.noteweave.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

class ArtifactOutboxDispatchSchedulerTest {

    @Test
    void schedulerShouldDelegateAndSkipOverlappingTicks() {
        ArtifactOutboxDispatcherService dispatcher = mock(ArtifactOutboxDispatcherService.class);
        TaskExecutor executor = mock(TaskExecutor.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        when(dispatcher.metrics()).thenReturn(new ArtifactOutboxMetricsResponse(0, 0, 0, 0, 0, 0));
        ArtifactOutboxDispatchScheduler scheduler = new ArtifactOutboxDispatchScheduler(dispatcher, executor);

        scheduler.dispatchReadyJobs();
        scheduler.dispatchReadyJobs();

        verify(executor, times(1)).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        submitted.get().run();
        scheduler.dispatchReadyJobs();
        verify(executor, times(2)).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void historicalDeadLettersShouldNotBeReportedAsNewFailures() {
        ArtifactOutboxDispatcherService dispatcher = mock(ArtifactOutboxDispatcherService.class);
        when(dispatcher.metrics()).thenReturn(
                metricsWithDeadLetters(10),
                metricsWithDeadLetters(10),
                metricsWithDeadLetters(11),
                metricsWithDeadLetters(9));
        ArtifactOutboxDispatchScheduler scheduler = new ArtifactOutboxDispatchScheduler(dispatcher, Runnable::run);

        Logger logger = (Logger) LoggerFactory.getLogger(ArtifactOutboxDispatchScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            scheduler.dispatchReadyJobs();
            scheduler.dispatchReadyJobs();
            scheduler.dispatchReadyJobs();
            scheduler.dispatchReadyJobs();
        } finally {
            logger.detachAppender(appender);
        }

        org.assertj.core.api.Assertions.assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Artifact outbox contains 10 existing dead-letter message(s); no new dead letters observed");
        org.assertj.core.api.Assertions.assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Artifact outbox dead-letter alert: 11 message(s) require operator review");
        org.assertj.core.api.Assertions.assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.INFO)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Artifact outbox dead-letter backlog decreased from 11 to 9");
    }

    private ArtifactOutboxMetricsResponse metricsWithDeadLetters(int count) {
        return new ArtifactOutboxMetricsResponse(0, 0, 0, count, 0, count);
    }
}
