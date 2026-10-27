package com.noteweave.knowledge;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.noteweave.source.SourceRetrievalReadyEvent;
import org.junit.jupiter.api.Test;

/** WIKI-04：READY 监听只做唤醒，建任务交给意图调度器，避免同一资料出现两个任务。 */
class WikiSourceReadyListenerTest {

    @Test
    void readyListenerOnlyWakesThePersistedIntent() {
        WikiIngestService ingest = mock(WikiIngestService.class);
        WikiReadyIntentWakeupPort wakeup = mock(WikiReadyIntentWakeupPort.class);
        when(wakeup.wake("workspace-1", "source-1")).thenReturn(true);
        WikiSourceReadyListener listener = new WikiSourceReadyListener(ingest);
        listener.setWakeup(wakeup);

        listener.onSourceReady(new SourceRetrievalReadyEvent("workspace-1", "source-1"));

        verify(wakeup).wake("workspace-1", "source-1");
        verifyNoInteractions(ingest);
    }

    @Test
    void readyListenerDoesNotCreateASecondTaskWhenNothingIsPending() {
        WikiIngestService ingest = mock(WikiIngestService.class);
        WikiReadyIntentWakeupPort wakeup = mock(WikiReadyIntentWakeupPort.class);
        when(wakeup.wake("workspace-1", "source-1")).thenReturn(false);
        WikiSourceReadyListener listener = new WikiSourceReadyListener(ingest);
        listener.setWakeup(wakeup);

        listener.onSourceReady(new SourceRetrievalReadyEvent("workspace-1", "source-1"));

        // 没有可唤醒的意图（已消费或处理中）时同样不建任务，缺口由调度器回填兜底
        verifyNoInteractions(ingest);
    }

    @Test
    void withoutTheIntentChannelTheListenerKeepsTheDirectEnqueue() {
        WikiIngestService ingest = mock(WikiIngestService.class);

        new WikiSourceReadyListener(ingest)
                .onSourceReady(new SourceRetrievalReadyEvent("workspace-1", "source-1"));

        verify(ingest).enqueueSourceIngestForReadySource("workspace-1", "source-1");
    }
}
