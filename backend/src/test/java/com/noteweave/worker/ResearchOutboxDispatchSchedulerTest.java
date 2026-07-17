package com.noteweave.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ResearchOutboxDispatchSchedulerTest {

    @Test
    void scheduledCycleShouldDispatchReadyRuns() {
        ResearchOutboxDispatcherService service = mock(ResearchOutboxDispatcherService.class);
        when(service.dispatchReadyResearchRuns(10)).thenReturn(new ResearchOutboxDispatchResponse(1));

        new ResearchOutboxDispatchScheduler(service).dispatchReadyRuns();

        verify(service).dispatchReadyResearchRuns(10);
    }
}
