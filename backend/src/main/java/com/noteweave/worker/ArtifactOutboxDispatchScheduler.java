package com.noteweave.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        name = "noteweave.worker.artifact-auto-dispatch-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ArtifactOutboxDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(ArtifactOutboxDispatchScheduler.class);

    private final ArtifactOutboxDispatcherService dispatcherService;
    private final TaskExecutor artifactDispatchExecutor;
    private final AtomicBoolean dispatchRunning = new AtomicBoolean();
    private int lastObservedDeadLetterCount = -1;

    public ArtifactOutboxDispatchScheduler(
            ArtifactOutboxDispatcherService dispatcherService,
            @Qualifier("artifactDispatchExecutor") TaskExecutor artifactDispatchExecutor
    ) {
        this.dispatcherService = dispatcherService;
        this.artifactDispatchExecutor = artifactDispatchExecutor;
    }

    @Scheduled(
            initialDelayString = "${noteweave.worker.artifact-dispatch-initial-delay-ms:1000}",
            fixedDelayString = "${noteweave.worker.artifact-dispatch-delay-ms:2000}"
    )
    public void dispatchReadyJobs() {
        if (!dispatchRunning.compareAndSet(false, true)) {
            log.debug("Artifact outbox dispatch is still running; skip overlapping scheduler tick");
            return;
        }
        try {
            artifactDispatchExecutor.execute(this::runDispatchCycle);
        } catch (TaskRejectedException ex) {
            dispatchRunning.set(false);
            log.warn("Artifact outbox dispatch executor rejected scheduler tick: {}", ex.getMessage());
        }
    }

    private void runDispatchCycle() {
        try {
            dispatcherService.dispatchReadyArtifactJobs(10);
            int deadLetterCount = dispatcherService.metrics().deadLetterCount();
            if (lastObservedDeadLetterCount < 0) {
                if (deadLetterCount > 0) {
                    log.warn("Artifact outbox contains {} existing dead-letter message(s); no new dead letters observed",
                            deadLetterCount);
                }
            } else if (deadLetterCount > lastObservedDeadLetterCount) {
                log.error("Artifact outbox dead-letter alert: {} message(s) require operator review", deadLetterCount);
            } else if (deadLetterCount < lastObservedDeadLetterCount) {
                log.info("Artifact outbox dead-letter backlog decreased from {} to {}",
                        lastObservedDeadLetterCount, deadLetterCount);
            }
            lastObservedDeadLetterCount = deadLetterCount;
        } catch (RuntimeException ex) {
            log.warn("Artifact outbox dispatch cycle failed: {}", ex.getMessage());
        } finally {
            dispatchRunning.set(false);
        }
    }
}
