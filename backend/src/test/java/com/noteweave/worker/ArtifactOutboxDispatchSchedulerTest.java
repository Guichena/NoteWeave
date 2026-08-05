package com.noteweave.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
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
}
