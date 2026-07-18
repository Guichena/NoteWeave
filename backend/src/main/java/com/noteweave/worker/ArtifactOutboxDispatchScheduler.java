package com.noteweave.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "noteweave.worker.artifact-auto-dispatch-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ArtifactOutboxDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArtifactOutboxDispatchScheduler.class);

    private final ArtifactOutboxDispatcherService dispatcherService;
    private int lastAlertedDeadLetterCount;

    public ArtifactOutboxDispatchScheduler(ArtifactOutboxDispatcherService dispatcherService) {
        this.dispatcherService = dispatcherService;
    }

    @Scheduled(
            initialDelayString = "${noteweave.worker.artifact-dispatch-initial-delay-ms:1000}",
            fixedDelayString = "${noteweave.worker.artifact-dispatch-delay-ms:2000}"
    )
    public void dispatchReadyJobs() {
        try {
            dispatcherService.dispatchReadyArtifactJobs(10);
            int deadLetterCount = dispatcherService.metrics().deadLetterCount();
            if (deadLetterCount > 0 && deadLetterCount != lastAlertedDeadLetterCount) {
                log.error("Artifact outbox dead-letter alert: {} message(s) require operator review", deadLetterCount);
            }
            lastAlertedDeadLetterCount = deadLetterCount;
        } catch (RuntimeException ex) {
            log.warn("Artifact outbox dispatch cycle failed: {}", ex.getMessage());
        }
    }
}
