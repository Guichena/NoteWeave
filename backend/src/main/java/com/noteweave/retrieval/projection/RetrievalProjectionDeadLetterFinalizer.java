package com.noteweave.retrieval.projection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.FinalizationOutcome;
import com.noteweave.source.SourceTopicNames;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 检索投影（向量化、索引、同步投影）消息无法完成时的统一收尾。
 * <p>
 * 消费者重试耗尽进入死信队列，和 outbox 投递耗尽，都必须走同一套收尾：
 * 把快照、当前资料和任务一起标记为失败，资料才能被重新处理或被重试调度器接手，
 * 而不是一直停在 PROCESSING / EMBEDDING / INDEXING。
 * 失败收尾后 {@link com.noteweave.source.SourceReprocessService} 在重新处理时会清空批次租约，
 * 所以残留的向量批次不会继续占着租约。
 * <p>
 * 收尾结果带语义（B-01）：APPLIED 表示失败收尾已提交；目标不存在、批次或任务代次已过时
 * 返回 IGNORED_STALE（无副作用）；数据库暂时错误等可重试异常返回 RETRYABLE_FAILURE，
 * 由补偿执行器退避后重试，不再把一切失败混成 boolean。
 */
public class RetrievalProjectionDeadLetterFinalizer {
    private static final Logger log = LoggerFactory.getLogger(RetrievalProjectionDeadLetterFinalizer.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("[a-f0-9]{64}");

    /** outbox 投递耗尽时使用的错误码。 */
    public static final String DELIVERY_EXHAUSTED_ERROR_CODE = "OUTBOX_DISPATCH_EXHAUSTED";

    private final ObjectMapper objectMapper;
    private final SourceRetrievalProjectionFinalizer projectionFinalizer;
    private final SourceTopicNames topicNames;
    private SourceEmbeddingBatchRepository embeddingBatches;

    public RetrievalProjectionDeadLetterFinalizer(
            ObjectMapper objectMapper,
            SourceRetrievalProjectionFinalizer projectionFinalizer,
            SourceTopicNames topicNames
    ) {
        this.objectMapper = objectMapper;
        this.projectionFinalizer = projectionFinalizer;
        this.topicNames = topicNames;
    }

    /** 批次守卫可以为空（例如没有配置向量批次仓储的测试环境）。 */
    public RetrievalProjectionDeadLetterFinalizer withEmbeddingBatches(
            SourceEmbeddingBatchRepository embeddingBatches) {
        this.embeddingBatches = embeddingBatches;
        return this;
    }

    /** 只有检索投影相关的主题才需要这套收尾，主题名一律取自配置。 */
    public boolean handles(String topic) {
        return topicNames.isRetrievalProjection(topic);
    }

    /**
     * 收尾一条无法完成的检索投影消息（载荷版本）。返回结果带语义；
     * 载荷无法识别按 IGNORED_STALE 处理，收尾抛异常按 RETRYABLE_FAILURE 处理，
     * 都不会向调用方抛出。
     */
    public FinalizationOutcome finalizeFailure(String topic, String payloadJson, String errorCode) {
        return finalizeFailure(topic, payloadJson, errorCode, null);
    }

    public FinalizationOutcome finalizeFailure(String topic, String payloadJson, String errorCode,
            com.noteweave.common.FinalizationFence fence) {
        Map<String, Object> payload = readPayload(topic, payloadJson);
        if (payload == null) {
            return FinalizationOutcome.IGNORED_STALE;
        }
        return finalizeFailureIdentity(topic, payload, errorCode, fence);
    }

    /**
     * 收尾的身份化入口（B-01）：补偿执行器只携带登记时冻结的业务身份，
     * 不复制全文载荷。identity 至少包含 workspaceId/sourceId/sourceSnapshotId(或 snapshotId)，
     * 向量化阶段消息还可携带 taskId/batchNo/batchVersion 参加批次代次核对。
     */
    public FinalizationOutcome finalizeFailureIdentity(String topic, Map<String, Object> identity, String errorCode) {
        return finalizeFailureIdentity(topic, identity, errorCode, null);
    }

    /**
     * 收尾的围栏入口（FB-01）：fence 在 finalizeFailed 事务内、锁定快照之后复核；
     * 人工重驱已换代时本次收尾无副作用跳过。
     */
    public FinalizationOutcome finalizeFailureIdentity(String topic, Map<String, Object> identity, String errorCode,
            com.noteweave.common.FinalizationFence fence) {
        try {
            return finalizeIdentityUnchecked(topic, identity, errorCode, fence);
        } catch (RuntimeException failure) {
            // 数据库暂时错误、锁竞争等可重试异常：交给补偿执行器退避重试
            log.warn("检索索引死信收尾暂时失败: topic={}", topic, failure);
            return FinalizationOutcome.RETRYABLE_FAILURE;
        }
    }

    private FinalizationOutcome finalizeIdentityUnchecked(String topic, Map<String, Object> payload,
            String errorCode, com.noteweave.common.FinalizationFence fence) {
        String workspaceId = text(payload.get("workspaceId"));
        String sourceId = text(payload.get("sourceId"));
        String snapshotId = text(payload.get("sourceSnapshotId"));
        if (isBlank(snapshotId)) {
            snapshotId = text(payload.get("snapshotId"));
        }
        String taskId = text(payload.get("taskId"));
        if (isBlank(workspaceId) || isBlank(sourceId) || isBlank(snapshotId)) {
            log.warn("检索投影消息缺少身份信息，按过期跳过收尾: topic={}, identity={}", topic, payload.keySet());
            return FinalizationOutcome.IGNORED_STALE;
        }
        if (embeddingBatches != null && topicNames.embed().equals(topic)
                && !embedBatchAcceptsFailure(workspaceId, sourceId, snapshotId, taskId, payload)) {
            // 完成批次的晚到失败、租约仍有效的批次、旧批次版本：过期事实，无副作用跳过
            return FinalizationOutcome.IGNORED_STALE;
        }
        if (embeddingBatches != null && topicNames.index().equals(topic)
                && !embeddingBatches.current(workspaceId, sourceId, snapshotId, taskId, "INDEXING")) {
            return FinalizationOutcome.IGNORED_STALE;
        }
        projectionFinalizer.finalizeFailed(workspaceId, sourceId, snapshotId, taskId, errorCode, fence);
        return FinalizationOutcome.APPLIED;
    }

    /** 完成批次的晚到失败、租约仍有效的批次都不能被收尾覆盖。 */
    private boolean embedBatchAcceptsFailure(
            String workspaceId,
            String sourceId,
            String snapshotId,
            String taskId,
            Map<String, Object> payload
    ) {
        Object batch = payload.get("batchNo");
        String version = text(payload.get("batchVersion"));
        if (batch == null) {
            return embeddingBatches.current(workspaceId, sourceId, snapshotId, taskId, "EMBEDDING");
        }
        if (!(batch instanceof Integer batchNo) || batchNo < 0 || version == null
                || !VERSION_PATTERN.matcher(version).matches()) {
            return false;
        }
        return embeddingBatches.acceptsFailure(workspaceId, sourceId, snapshotId, taskId, batchNo, version);
    }

    private Map<String, Object> readPayload(String topic, String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<>() { });
        } catch (RuntimeException | IOException parseFailure) {
            log.warn("检索投影消息载荷无法解析: topic={}", topic, parseFailure);
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
