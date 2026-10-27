package com.noteweave.infra.usage;

import java.time.Instant;

/**
 * C5 真实 usage 的持久化端口（FB-07，B 侧接口合同）。
 *
 * <p>A 的 ActualUsageRecorder 在记录时调用本端口把供应商真实上报落到
 * actual_usage_fact（JDBC 实现，迁移 V206）；A 的流式客户端与 Recorder 归 A 所有。
 * 数据库故障不得伪造已持久化：端口返回明确的落库结果，调用方保留受限重试与
 * 失败指标，不影响聊天主链路可用性（合同细节见 contracts-ready.md）。
 */
public interface ActualUsageStore {

    /**
     * 记录一次请求尝试的真实 usage，按 requestAttemptId 幂等：
     * 同 attempt 重复记录返回 DUPLICATE；更完整记录受控升级局部记录返回 UPGRADED；
     * COMPLETE 不被 UNKNOWN/PARTIAL 覆盖；同等级不同计数是可观察冲突，保留首见值。
     */
    Result record(ActualUsageRecord record);

    /** 按 attempt 身份读取既有记录；不存在返回空（A 的 conversation 端口适配用）。 */
    java.util.Optional<ActualUsageRecord> findByAttemptId(String requestAttemptId);

    /** 单工作台分页读：按时间倒序、只含聚合字段，供对账与用量展示。 */
    java.util.List<ActualUsageRecord> listByWorkspace(
            String workspaceId, Instant before, int limit);

    /** 落库结果语义。 */
    enum Result { STORED, DUPLICATE, UPGRADED, CONFLICT }

    /**
     * usage 事实。inputTokens/outputTokens 等为 NULL 表示供应商未上报该项，
     * 不能以 0 伪造；workspaceId 为 null 的内部调用必须给出 business_context 归属。
     */
    record ActualUsageRecord(
            String requestAttemptId,
            String workspaceId,
            String businessContext,
            String provider,
            String requestedModel,
            String returnedModel,
            Long inputTokens,
            Long outputTokens,
            Long cachedInputTokens,
            Long reasoningTokens,
            boolean reported,
            String completeness,
            String usageSchemaVersion,
            Instant occurredAt
    ) {
    }
}
