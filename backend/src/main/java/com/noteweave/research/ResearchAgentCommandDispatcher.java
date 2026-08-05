package com.noteweave.research;

import com.noteweave.infra.KafkaMessagePublisher;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Publishes MA4 agent command outbox rows with at-least-once delivery semantics. */
@Service
@ConditionalOnProperty(name = "noteweave.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ResearchAgentCommandDispatcher {

    private final DurableOutboxDispatcher outboxDispatcher;
    private final KafkaMessagePublisher publisher;
    private final ResearchAgentLifecycleService lifecycleService;

    public ResearchAgentCommandDispatcher(JdbcTemplate jdbcTemplate, KafkaMessagePublisher publisher) {
        this(new DurableOutboxDispatcher(jdbcTemplate, new SimpleMeterRegistry()), publisher, null);
    }

    @Autowired
    public ResearchAgentCommandDispatcher(
            DurableOutboxDispatcher outboxDispatcher,
            KafkaMessagePublisher publisher,
            ResearchAgentLifecycleService lifecycleService
    ) {
        this.outboxDispatcher = outboxDispatcher;
        this.publisher = publisher;
        this.lifecycleService = lifecycleService;
    }

    public DispatchResponse dispatchReady(int limit) {
        return dispatch(null, limit);
    }

    /** Canary-safe dispatch boundary: never publishes READY rows owned by another run. */
    public DispatchResponse dispatchReadyForRun(String runId, int limit) {
        return dispatch(runId, limit);
    }

    private DispatchResponse dispatch(String runId, int limit) {
        DurableOutboxDispatcher.DispatchResult result = outboxDispatcher.dispatchAgentCommands(
                runId,
                limit,
                message -> publisher.publish(
                        message.topic(), message.messageKey(), message.payloadJson()
                ),
                message -> {
                    if (lifecycleService != null && message.taskId() != null) {
                        lifecycleService.failCommandDispatchExhausted(
                                message.taskId(), message.outboxId(), message.attemptNo());
                    }
                }
        );
        return new DispatchResponse(result.publishedCount());
    }

    public record DispatchResponse(int dispatchedCount) { }
}
