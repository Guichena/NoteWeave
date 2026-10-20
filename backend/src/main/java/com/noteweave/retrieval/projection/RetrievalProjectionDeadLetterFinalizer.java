package com.noteweave.retrieval.projection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.source.SourcePipelineStages;
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
 * 所以残留的向量批次不会继续占着租约。收尾异常只记录日志，不能打断消费或投递循环。
 */
public class RetrievalProjectionDeadLetterFinalizer {
    private static final Logger log = LoggerFactory.getLogger(RetrievalProjectionDeadLetterFinalizer.class);
    private static final Pattern VERSION_PATTERN = Pattern.compile("[a-f0-9]{64}");

    /** outbox 投递耗尽时使用的错误码。 */
    public static final String DELIVERY_EXHAUSTED_ERROR_CODE = "OUTBOX_DISPATCH_EXHAUSTED";

    private final ObjectMapper objectMapper;
    private final SourceRetrievalProjectionFinalizer projectionFinalizer;
    private final String retrievalProjectionTopic;
    private SourceEmbeddingBatchRepository embeddingBatches;

    public RetrievalProjectionDeadLetterFinalizer(
            ObjectMapper objectMapper,
            SourceRetrievalProjectionFinalizer projectionFinalizer,
            String retrievalProjectionTopic
    ) {
        this.objectMapper = objectMapper;
        this.projectionFinalizer = projectionFinalizer;
        this.retrievalProjectionTopic = retrievalProjectionTopic;
    }

    /** 批次守卫可以为空（例如没有配置向量批次仓储的测试环境）。 */
    public RetrievalProjectionDeadLetterFinalizer withEmbeddingBatches(
            SourceEmbeddingBatchRepository embeddingBatches) {
        this.embeddingBatches = embeddingBatches;
        return this;
    }

    /** 只有检索投影相关的主题才需要这套收尾。 */
    public boolean handles(String topic) {
        return topic != null && (topic.equals(retrievalProjectionTopic)
                || SourcePipelineStages.TOPIC_EMBED.equals(topic)
                || SourcePipelineStages.TOPIC_INDEX.equals(topic));
    }

    /**
     * 收尾一条无法完成的检索投影消息。返回 true 表示已经完成失败收尾；
     * 载荷无法识别、批次已过时或收尾抛异常时返回 false，且不会向调用方抛出。
     */
    public boolean finalizeFailure(String topic, String payloadJson, String errorCode) {
        try {
            return finalizeUnchecked(topic, payloadJson, errorCode);
        } catch (RuntimeException failure) {
            log.warn("检索索引投递耗尽收尾失败: topic={}", topic, failure);
            return false;
        }
    }

    private boolean finalizeUnchecked(String topic, String payloadJson, String errorCode) {
        Map<String, Object> payload = readPayload(topic, payloadJson);
        if (payload == null) {
            return false;
        }
        String workspaceId = text(payload.get("workspaceId"));
        String sourceId = text(payload.get("sourceId"));
        String snapshotId = text(payload.get("sourceSnapshotId"));
        if (isBlank(snapshotId)) {
            snapshotId = text(payload.get("snapshotId"));
        }
        String taskId = text(payload.get("taskId"));
        if (isBlank(workspaceId) || isBlank(sourceId) || isBlank(snapshotId)) {
            log.warn("检索投影消息缺少身份信息，跳过收尾: topic={}, payload={}", topic, payloadJson);
            return false;
        }
        if (embeddingBatches != null && SourcePipelineStages.TOPIC_EMBED.equals(topic)
                && !embedBatchAcceptsFailure(workspaceId, sourceId, snapshotId, taskId, payload)) {
            return false;
        }
        if (embeddingBatches != null && SourcePipelineStages.TOPIC_INDEX.equals(topic)
                && !embeddingBatches.current(workspaceId, sourceId, snapshotId, taskId, "INDEXING")) {
            return false;
        }
        projectionFinalizer.finalizeFailed(workspaceId, sourceId, snapshotId, taskId, errorCode);
        return true;
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
