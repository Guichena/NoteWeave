package com.noteweave.research;

import com.noteweave.infra.KafkaMessagePublisher;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.infra.outbox.OutboxFinalizationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private OutboxFinalizationRepository finalizations;

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

    /** 补偿登记仓储：注入后 agent 死信从内联收尾切换为持久补偿（B-01）。 */
    @Autowired(required = false)
    void setFinalizations(OutboxFinalizationRepository finalizations) {
        this.finalizations = finalizations;
    }

    private DispatchResponse dispatch(String runId, int limit) {
        DurableOutboxDispatcher.DispatchResult result = outboxDispatcher.dispatchAgentCommands(
                runId,
                limit,
                message -> publisher.publish(
                        message.topic(), message.messageKey(), message.payloadJson()
                ),
                message -> {
                    // 死信处理器在 DEAD_LETTER 事务内被调用，只登记补偿意图（B-01）：
                    // 业务收尾（failCommandDispatchExhausted 校验 deliveryNo 后终态化）
                    // 由 OutboxFinalizationExecutor 通过 ResearchAgentDispatchFinalizer 异步执行，
                    // 旧 deliveryNo 的死信不能终止新的 Research 投递。
                    if (finalizations != null && message.taskId() != null) {
                        Map<String, Object> identity = new LinkedHashMap<>();
                        identity.put("taskId", message.taskId());
                        identity.put("outboxId", message.outboxId());
                        identity.put("attemptNo", message.attemptNo());
                        identity.put("deliveryNo", message.deliveryNo());
                        finalizations.insertPending(
                                OutboxFinalizationRepository.STORE_AGENT,
                                message.outboxId(),
                                message.deliveryNo(),
                                ResearchAgentDispatchFinalizer.KIND,
                                null,
                                identity,
                                5);
                    } else if (lifecycleService != null && message.taskId() != null) {
                        // 兼容未装配补偿仓储的测试构造
                        lifecycleService.failCommandDispatchExhausted(
                                message.taskId(), message.outboxId(), message.attemptNo());
                    }
                }
        );
        return new DispatchResponse(result.publishedCount());
    }

    public record DispatchResponse(int dispatchedCount) { }
}
