package com.noteweave.research;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Automatic outbox pump for the sole opt-in execution mode, INCREMENTAL_V1.
 * The dispatcher itself filters by that durable mode, so legacy runs cannot
 * become dispatchable merely because scheduling is active.
 */
@Component
@ConditionalOnProperty(name = "noteweave.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ResearchAgentCommandDispatchScheduler {
    private final ResearchAgentCommandDispatcher dispatcher;
    private final int batchSize;

    public ResearchAgentCommandDispatchScheduler(
            ResearchAgentCommandDispatcher dispatcher,
            @Value("${noteweave.research.agent.command-dispatch-batch-size:25}") int batchSize) {
        this.dispatcher = dispatcher;
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("research agent command dispatch batch size must be between 1 and 100");
        }
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${noteweave.research.agent.command-dispatch-delay-ms:1000}")
    public void dispatchReadyCommands() {
        dispatcher.dispatchReady(batchSize);
    }
}
