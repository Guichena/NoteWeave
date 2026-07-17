package com.noteweave.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Dispatches user-created Deep Research runs without requiring an internal manual endpoint call. */
@Component
@ConditionalOnProperty(
        name = "noteweave.worker.research-auto-dispatch-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ResearchOutboxDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResearchOutboxDispatchScheduler.class);

    private final ResearchOutboxDispatcherService dispatcherService;

    public ResearchOutboxDispatchScheduler(ResearchOutboxDispatcherService dispatcherService) {
        this.dispatcherService = dispatcherService;
    }

    @Scheduled(
            initialDelayString = "${noteweave.worker.research-dispatch-initial-delay-ms:1000}",
            fixedDelayString = "${noteweave.worker.research-dispatch-delay-ms:2000}"
    )
    public void dispatchReadyRuns() {
        try {
            dispatcherService.dispatchReadyResearchRuns(10);
        } catch (RuntimeException ex) {
            log.warn("Research outbox dispatch cycle failed: {}", ex.getMessage());
        }
    }
}
