package com.noteweave.infra.usage;

import com.noteweave.conversation.ActualUsage;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A 的 usage 端口适配（FB 组合接入，回应 A NEE-4/反向复查 #3）：
 * 将 conversation.ActualUsageStore（insertIfAbsent/find/upgradeIfSuperior）
 * 映射到 B 的 JdbcActualUsageStore（V206）。单调升级语义由 B 仓储内建：
 * COMPLETE>PARTIAL>UNKNOWN 才覆盖；同等级不同值 conflict_count 可观察不累加。
 */
@Component
public class ConversationActualUsageStoreAdapter implements com.noteweave.conversation.ActualUsageStore {

    private final JdbcActualUsageStore store;

    public ConversationActualUsageStoreAdapter(JdbcActualUsageStore store) {
        this.store = store;
    }

    @Override
    public boolean insertIfAbsent(String attemptId, String workspaceId, ActualUsage usage, Instant occurredAt) {
        ActualUsageStore.Result result = store.record(toRecord(attemptId, workspaceId, usage, occurredAt));
        return result == ActualUsageStore.Result.STORED;
    }

    @Override
    public Optional<ActualUsage> find(String attemptId) {
        return store.findByAttemptId(attemptId).map(record -> new ActualUsage(
                record.returnedModel() == null ? record.requestedModel() : record.returnedModel(),
                record.inputTokens(), record.outputTokens(),
                record.reported(),
                record.completeness() == null ? ActualUsage.Completeness.UNKNOWN
                        : ActualUsage.Completeness.valueOf(record.completeness())));
    }

    @Override
    public boolean upgradeIfSuperior(String attemptId, String workspaceId, ActualUsage usage, Instant occurredAt) {
        ActualUsageStore.Result result = store.record(toRecord(attemptId, workspaceId, usage, occurredAt));
        // STORED（此前无记录）与 UPGRADED（严格更完整）都算升级成功；
        // DUPLICATE/CONFLICT（同等级或降级）返回 false，由 A 侧 Recorder 告警
        return result == ActualUsageStore.Result.STORED || result == ActualUsageStore.Result.UPGRADED;
    }

    private ActualUsageStore.ActualUsageRecord toRecord(
            String attemptId, String workspaceId, ActualUsage usage, Instant occurredAt) {
        return new ActualUsageStore.ActualUsageRecord(
                attemptId,
                workspaceId,
                workspaceId == null || workspaceId.isBlank() ? "INTERNAL_CHAT" : "CHAT",
                null,
                usage.modelId(),
                usage.modelId(),
                usage.inputTokens(),
                usage.outputTokens(),
                null,
                null,
                usage.reported(),
                usage.completeness() == null ? "UNKNOWN" : usage.completeness().name(),
                null,
                occurredAt);
    }
}
