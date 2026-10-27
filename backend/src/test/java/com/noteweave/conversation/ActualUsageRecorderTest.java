package com.noteweave.conversation;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FA-09 定向验证：真实 usage 记录的幂等、受控升级与同等级冲突拒绝。
 * 覆盖重复 COMPLETE 去重、PARTIAL→COMPLETE 升级、迟到 UNKNOWN 不覆盖、
 * 同等级不同值拒绝；回退路径的进程内有界去重同样验证。
 */
class ActualUsageRecorderTest {

    private static final ActualUsage COMPLETE =
            ActualUsage.reported("glm-5", 100, 40);
    private static final ActualUsage COMPLETE_DIFFERENT =
            ActualUsage.reported("glm-5", 111, 44);
    private static final ActualUsage PARTIAL =
            new ActualUsage("glm-5", 90L, 30L, true, ActualUsage.Completeness.PARTIAL);
    private static final ActualUsage UNKNOWN =
            ActualUsage.unreported("glm-5", ActualUsage.Completeness.UNKNOWN);

    @Test
    void duplicateCompleteIsIgnored() {
        CountingStore store = new CountingStore();
        ActualUsageRecorder recorder = new ActualUsageRecorder(null, store);
        org.junit.jupiter.api.Assertions.assertTrue(recorder.record("attempt-1", "ws-1", COMPLETE));
        org.junit.jupiter.api.Assertions.assertFalse(recorder.record("attempt-1", "ws-1", COMPLETE));
        org.junit.jupiter.api.Assertions.assertEquals(1, store.inserts.get());
        org.junit.jupiter.api.Assertions.assertEquals(0, store.upgrades.get());
    }

    @Test
    void partialUpgradesToComplete() {
        CountingStore store = new CountingStore();
        store.saved.put("attempt-1", PARTIAL);
        ActualUsageRecorder recorder = new ActualUsageRecorder(null, store);
        org.junit.jupiter.api.Assertions.assertTrue(recorder.record("attempt-1", "ws-1", COMPLETE));
        org.junit.jupiter.api.Assertions.assertEquals(1, store.upgrades.get());
    }

    @Test
    void lateUnknownDoesNotOverrideComplete() {
        CountingStore store = new CountingStore();
        store.saved.put("attempt-1", COMPLETE);
        ActualUsageRecorder recorder = new ActualUsageRecorder(null, store);
        org.junit.jupiter.api.Assertions.assertFalse(recorder.record("attempt-1", "ws-1", UNKNOWN));
        org.junit.jupiter.api.Assertions.assertEquals(0, store.upgrades.get());
    }

    @Test
    void sameLevelDifferentValuesRejected() {
        CountingStore store = new CountingStore();
        store.saved.put("attempt-1", COMPLETE);
        ActualUsageRecorder recorder = new ActualUsageRecorder(null, store);
        org.junit.jupiter.api.Assertions.assertFalse(recorder.record("attempt-1", "ws-1", COMPLETE_DIFFERENT));
        org.junit.jupiter.api.Assertions.assertEquals(0, store.upgrades.get());
    }

    @Test
    void unknownUpgradesToPartialThenComplete() {
        CountingStore store = new CountingStore();
        store.saved.put("attempt-1", UNKNOWN);
        ActualUsageRecorder recorder = new ActualUsageRecorder(null, store);
        org.junit.jupiter.api.Assertions.assertTrue(recorder.record("attempt-1", "ws-1", PARTIAL));
        store.saved.put("attempt-1", PARTIAL);
        org.junit.jupiter.api.Assertions.assertTrue(recorder.record("attempt-1", "ws-1", COMPLETE));
        org.junit.jupiter.api.Assertions.assertEquals(2, store.upgrades.get());
    }

    /** USAGE-01：缺工作区时不落孤儿 usage 行，带工作区才写。 */
    @Test
    void missingWorkspaceIsRefusedInsteadOfPersistingAnOrphanRow() {
        CountingStore store = new CountingStore();
        ActualUsageRecorder recorder = new ActualUsageRecorder(null, store);

        org.junit.jupiter.api.Assertions.assertFalse(recorder.record("attempt-1", null, COMPLETE));
        org.junit.jupiter.api.Assertions.assertFalse(recorder.record("attempt-1", "   ", COMPLETE));
        org.junit.jupiter.api.Assertions.assertEquals(0, store.inserts.get());
        org.junit.jupiter.api.Assertions.assertTrue(recorder.record("attempt-1", "ws-1", COMPLETE));
        org.junit.jupiter.api.Assertions.assertEquals(1, store.inserts.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void inMemoryFallbackDedupesWithoutClearingAll() {
        ObjectProvider<ActualUsageStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ActualUsageRecorder recorder = new ActualUsageRecorder(null, provider);
        org.junit.jupiter.api.Assertions.assertTrue(recorder.record("a-1", "ws-1", COMPLETE));
        org.junit.jupiter.api.Assertions.assertFalse(recorder.record("a-1", "ws-1", COMPLETE));
        org.junit.jupiter.api.Assertions.assertTrue(recorder.record("a-2", "ws-1", COMPLETE));
        // 超窗淘汰最旧而不是全清：写入 12000 个身份后 a-2 被淘汰可重记，机制仍是去重
        for (int i = 0; i < 12_000; i++) {
            recorder.record("bulk-" + i, "ws-1", COMPLETE);
        }
        org.junit.jupiter.api.Assertions.assertTrue(recorder.record("a-2", "ws-1", COMPLETE));
    }

    /** 简单计数存储桩：模拟 B 侧 JDBC 实现的幂等与升级语义。 */
    private static final class CountingStore implements ActualUsageStore {
        final Map<String, ActualUsage> saved = new HashMap<>();
        final AtomicInteger inserts = new AtomicInteger();
        final AtomicInteger upgrades = new AtomicInteger();

        @Override
        public boolean insertIfAbsent(String attemptId, String workspaceId, ActualUsage usage,
                                      Instant occurredAt) {
            if (saved.containsKey(attemptId)) {
                return false;
            }
            saved.put(attemptId, usage);
            inserts.incrementAndGet();
            return true;
        }

        @Override
        public Optional<ActualUsage> find(String attemptId) {
            return Optional.ofNullable(saved.get(attemptId));
        }

        @Override
        public boolean upgradeIfSuperior(String attemptId, String workspaceId, ActualUsage usage,
                                         Instant occurredAt) {
            ActualUsage existing = saved.get(attemptId);
            if (existing == null || order(usage) <= order(existing)) {
                return false;
            }
            saved.put(attemptId, usage);
            upgrades.incrementAndGet();
            return true;
        }

        private int order(ActualUsage usage) {
            return switch (usage.completeness()) {
                case UNKNOWN -> 0;
                case PARTIAL -> 1;
                case COMPLETE -> 2;
            };
        }
    }
}
