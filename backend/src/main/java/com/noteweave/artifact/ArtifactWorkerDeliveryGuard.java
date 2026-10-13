package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import com.noteweave.config.ProductionConfigurationGuard;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import com.noteweave.infra.outbox.OutboxDispatchPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 读取与检索共用投递所有权检查，避免旧 Worker 继续读取任务资料。 */
@Component
public class ArtifactWorkerDeliveryGuard {
    private final DurableOutboxDispatcher dispatcher;
    private final String environment;
    private final boolean unfencedEnabled;

    public ArtifactWorkerDeliveryGuard(DurableOutboxDispatcher dispatcher,
            @Value("${noteweave.environment:development}") String environment,
            @Value("${noteweave.worker.artifact-input-unfenced-enabled:false}") boolean unfencedEnabled) {
        this.dispatcher = dispatcher;
        this.environment = environment == null ? "development" : environment;
        this.unfencedEnabled = unfencedEnabled;
    }

    public void requireDelivery(String taskId, String deliveryToken) {
        if (deliveryToken == null || deliveryToken.isBlank()) {
            if (unfencedEnabled && !ProductionConfigurationGuard.isProduction(environment)) return;
            throw new BusinessException("WORKER_INPUT_DELIVERY_TOKEN_REQUIRED",
                    "生产环境的产物输入请求必须携带有效的 outbox 投递令牌", HttpStatus.CONFLICT);
        }
        if (!dispatcher.renewTaskMessage("noteweave.artifact.job", taskId,
                deliveryToken, OutboxDispatchPolicy.ARTIFACT_LEASE_DURATION)) {
            throw new BusinessException("WORKER_INPUT_DELIVERY_STALE",
                    "产物输入请求不属于当前有效的 outbox 投递", HttpStatus.CONFLICT);
        }
    }
}
