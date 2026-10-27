package com.noteweave.conversation;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * C5 真实 usage 记录器：按请求尝试身份幂等，缺 usage 明确标记未知。
 *
 * <p>持久去重与受控升级经由 {@link ActualUsageStore}（B 提供 JDBC 实现）：
 * 同 attemptId 只首次生效；PARTIAL/UNKNOWN 可被同 attemptId 的后到 COMPLETE 升级，
 * 同等级不同值拒绝并告警，迟到 UNKNOWN 不覆盖既有记录。
 * 存储未接线时保留有界的进程内去重（按插入序淘汰最旧身份，不再整表清空），
 * 该回退不宣称持久化，重启即丢，接线状态见交接 completion-matrix。</p>
 */
@Service
public class ActualUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(ActualUsageRecorder.class);

    /** 回退去重窗口的身份数量上限；超出按插入序淘汰最旧，不做全清。 */
    private static final int MAX_IN_MEMORY_IDENTITIES = 10_000;

    private final MeterRegistry meterRegistry;
    private final ActualUsageStore store;
    private final Map<String, ActualUsage> inMemoryIdentities;

    public ActualUsageRecorder() {
        this(null, (ActualUsageStore) null);
    }

    @Autowired
    public ActualUsageRecorder(MeterRegistry meterRegistry,
                               org.springframework.beans.factory.ObjectProvider<ActualUsageStore> storeProvider) {
        this(meterRegistry, storeProvider == null ? null : storeProvider.getIfAvailable());
    }

    public ActualUsageRecorder(MeterRegistry meterRegistry, ActualUsageStore store) {
        this.meterRegistry = meterRegistry;
        this.store = store;
        this.inMemoryIdentities = new LinkedHashMap<>(1024) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ActualUsage> eldest) {
                return size() > MAX_IN_MEMORY_IDENTITIES;
            }
        };
    }

    /**
     * 记录一次请求尝试的真实 usage。
     *
     * <p>USAGE-01：持久化必须带工作区归属，否则对账时看不到这笔消耗。缺工作区时拒绝写入并告警，
     * 不落孤儿行；进程内回退路径不涉及归属，不受此限制。
     *
     * @return true 表示写入或升级成功；false 表示重复、降级、缺工作区或同等级不同值被拒绝
     */
    public boolean record(String attemptId, String workspaceId, ActualUsage usage) {
        if (attemptId == null || attemptId.isBlank() || usage == null) {
            return false;
        }
        if (store != null) {
            if (workspaceId == null || workspaceId.isBlank()) {
                // USAGE-01：持久化行没有工作区就没法对账；宁可拒绝并告警，也不要写孤儿 usage
                log.warn("拒绝记录缺少工作区归属的真实 usage: attemptId={}, model={}, completeness={}",
                        attemptId, usage.modelId(), usage.completeness());
                if (meterRegistry != null) {
                    meterRegistry.counter("noteweave.usage.actual.rejected_missing_workspace").increment();
                }
                return false;
            }
            return recordThroughStore(attemptId, workspaceId, usage);
        }
        return recordInMemory(attemptId, usage);
    }

    private boolean recordThroughStore(String attemptId, String workspaceId, ActualUsage usage) {
        Optional<ActualUsage> existing = store.find(attemptId);
        if (existing.isEmpty()) {
            boolean inserted = store.insertIfAbsent(attemptId, workspaceId, usage, Instant.now());
            if (inserted) {
                observe(attemptId, usage, "inserted");
            }
            return inserted;
        }
        ActualUsage previous = existing.get();
        int order = completenessOrder(usage.completeness()) - completenessOrder(previous.completeness());
        if (order <= 0) {
            if (order == 0 && !sameFacts(previous, usage)) {
                log.warn("LLM usage same-level conflict rejected: attemptId={}, previous={}/{}, incoming={}/{}",
                        attemptId, previous.completeness(), previous.inputTokens(),
                        usage.completeness(), usage.inputTokens());
            }
            return false;
        }
        boolean upgraded = store.upgradeIfSuperior(attemptId, workspaceId, usage, Instant.now());
        if (upgraded) {
            observe(attemptId, usage, "upgraded");
        }
        return upgraded;
    }

    private boolean recordInMemory(String attemptId, ActualUsage usage) {
        synchronized (inMemoryIdentities) {
            ActualUsage previous = inMemoryIdentities.get(attemptId);
            if (previous == null) {
                inMemoryIdentities.put(attemptId, usage);
                observe(attemptId, usage, "in-memory");
                return true;
            }
            int order = completenessOrder(usage.completeness()) - completenessOrder(previous.completeness());
            if (order > 0) {
                inMemoryIdentities.put(attemptId, usage);
                observe(attemptId, usage, "in-memory-upgraded");
                return true;
            }
            return false;
        }
    }

    private int completenessOrder(ActualUsage.Completeness completeness) {
        return switch (completeness) {
            case UNKNOWN -> 0;
            case PARTIAL -> 1;
            case COMPLETE -> 2;
        };
    }

    private boolean sameFacts(ActualUsage left, ActualUsage right) {
        return left.reported() == right.reported()
                && java.util.Objects.equals(left.inputTokens(), right.inputTokens())
                && java.util.Objects.equals(left.outputTokens(), right.outputTokens());
    }

    private void observe(String attemptId, ActualUsage usage, String outcome) {
        log.info("LLM actual usage: attemptId={}, outcome={}, model={}, reported={}, completeness={}, "
                        + "inputTokens={}, outputTokens={}",
                attemptId,
                outcome,
                usage.modelId() == null ? "-" : usage.modelId(),
                usage.reported(),
                usage.completeness(),
                usage.inputTokens() == null ? "-" : usage.inputTokens(),
                usage.outputTokens() == null ? "-" : usage.outputTokens());
        if (meterRegistry != null) {
            meterRegistry.counter("noteweave.llm.usage.reported",
                    "reported", String.valueOf(usage.reported()),
                    "completeness", usage.completeness().name()).increment();
            if (usage.reported() && usage.inputTokens() != null && usage.outputTokens() != null) {
                meterRegistry.summary("noteweave.llm.usage.input_tokens").record(usage.inputTokens());
                meterRegistry.summary("noteweave.llm.usage.output_tokens").record(usage.outputTokens());
            }
        }
    }

}
