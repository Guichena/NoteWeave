package com.noteweave.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 语义键与载荷摘要的纯函数测试（B-02/C2 验收）：
 * 字段拼接歧义、volatile 键剔除、batchNo 维度区分都必须可证明。
 */
class OutboxSemanticKeyTest {

    @Test
    void fieldConcatenationAmbiguityMustProduceDifferentKeys() {
        // "ab"+"c" 与 "a"+"bc" 不能因拼接产生相同语义键
        String left = OutboxSemanticKey.semanticKey("ws", "ab-source", "c", null, "g", "PARSE", null);
        String right = OutboxSemanticKey.semanticKey("ws", "a", "bc-source", null, "g", "PARSE", null);
        assertThat(left).isNotEqualTo(right);
        // 每段自定界：分隔符出现在字段值里也不产生歧义
        String withSeparator = OutboxSemanticKey.semanticKey("ws", "a:b", "c", null, "g", "PARSE", null);
        String crossed = OutboxSemanticKey.semanticKey("ws", "a", "b:c", null, "g", "PARSE", null);
        assertThat(withSeparator).isNotEqualTo(crossed);
    }

    @Test
    void volatileKeysMustNotChangePayloadDigest() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("workspaceId", "ws-1");
        first.put("sourceId", "src-1");
        first.put("batchVersion", "v1");
        first.put("correlationId", "trace-aaa");
        first.put("sentAt", "2026-10-24T00:00:00Z");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("batchVersion", "v1");
        second.put("sourceId", "src-1");
        second.put("workspaceId", "ws-1");
        second.put("correlationId", "trace-bbb");
        second.put("deliveryToken", "token-xyz");
        assertThat(OutboxSemanticKey.payloadDigest(first))
                .isEqualTo(OutboxSemanticKey.payloadDigest(second));
    }

    @Test
    void businessFieldChangeMustChangeDigest() {
        Map<String, Object> first = new LinkedHashMap<>(Map.of("workspaceId", "ws-1", "batchVersion", "v1"));
        Map<String, Object> second = new LinkedHashMap<>(Map.of("workspaceId", "ws-1", "batchVersion", "v2"));
        assertThat(OutboxSemanticKey.payloadDigest(first))
                .isNotEqualTo(OutboxSemanticKey.payloadDigest(second));
    }

    @Test
    void batchNoDimensionMustDistinguishIdentity() {
        String withoutBatch = OutboxSemanticKey.semanticKey("ws", "src", "snap", null, "g", "EMBED_BATCH", null);
        String batchZero = OutboxSemanticKey.semanticKey("ws", "src", "snap", null, "g", "EMBED_BATCH", 0);
        String batchOne = OutboxSemanticKey.semanticKey("ws", "src", "snap", null, "g", "EMBED_BATCH", 1);
        assertThat(withoutBatch).isNotEqualTo(batchZero);
        assertThat(batchZero).isNotEqualTo(batchOne);
    }

    @Test
    void crossWorkspaceSameSourceStringMustDiffer() {
        String a = OutboxSemanticKey.semanticKey("ws-1", "shared-source-id", "snap", null, "g", "PARSE", null);
        String b = OutboxSemanticKey.semanticKey("ws-2", "shared-source-id", "snap", null, "g", "PARSE", null);
        assertThat(a).isNotEqualTo(b);
    }
}
