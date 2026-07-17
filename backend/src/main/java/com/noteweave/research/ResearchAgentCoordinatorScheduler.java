package com.noteweave.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stateless single-database recovery loop for INCREMENTAL_V1 runs. The run lock
 * inside the tick is the only linearization mechanism; this scheduler never owns
 * caller-provided wave, scope, budget, or decision data.
 */
@Component
@ConditionalOnProperty(name = "noteweave.research.agent.coordinator-enabled", havingValue = "true", matchIfMissing = true)
public class ResearchAgentCoordinatorScheduler {
    private static final Logger log = LoggerFactory.getLogger(ResearchAgentCoordinatorScheduler.class);
    private final ResearchAgentLifecycleService lifecycleService;
    private final ResearchAgentCoordinatorRunScanner scanner;
    private final ResearchAgentCoordinatorTickService tickService;
    private final String coordinatorInstanceId;
    private final int runBatchSize;

    public ResearchAgentCoordinatorScheduler(
            ResearchAgentLifecycleService lifecycleService,
            ResearchAgentCoordinatorRunScanner scanner,
            ResearchAgentCoordinatorTickService tickService,
            @Value("${noteweave.research.agent.coordinator-instance-id:local-coordinator}") String coordinatorInstanceId,
            @Value("${noteweave.research.agent.coordinator-run-batch-size:25}") int runBatchSize) {
        if (coordinatorInstanceId == null || coordinatorInstanceId.isBlank()) {
            throw new IllegalArgumentException("research agent coordinator instance id must not be blank");
        }
        if (runBatchSize < 1 || runBatchSize > 100) {
            throw new IllegalArgumentException("research agent coordinator run batch size must be between 1 and 100");
        }
        this.lifecycleService = lifecycleService;
        this.scanner = scanner;
        this.tickService = tickService;
        this.coordinatorInstanceId = coordinatorInstanceId.trim();
        this.runBatchSize = runBatchSize;
    }

    @Scheduled(fixedDelayString = "${noteweave.research.agent.coordinator-delay-ms:1000}")
    public void recoverEligibleRuns() {
        lifecycleService.reapExpiredLeases();
        for (String runId : scanner.findEligibleRunIds(runBatchSize)) {
            try {
                tickService.tick(runId, coordinatorInstanceId);
            } catch (RuntimeException exception) {
                // The next durable tick may replay this run; do not starve peers.
                log.warn("Research agent coordinator tick failed for run {}", runId, exception);
            }
        }
    }
}
