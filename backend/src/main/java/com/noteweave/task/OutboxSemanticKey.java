package com.noteweave.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 统一入队身份的规范化编码与摘要（C2）。
 *
 * <p>semanticKey 覆盖 workspace、业务对象、task、generation、stage、batch 六类业务维度；
 * 每个字段按 UTF-8 字节长度前缀自定界后拼接再哈希，字段值里的分隔符不会造成
 * 「ab + c」与「a + bc」这类拼接歧义。partitionKey 与语义身份无关，只负责 Kafka 分区。
 *
 * <p>payloadDigest 只比较同业务代次的规范化业务字段：volatile 键（trace、correlation、
 * 时间戳等）排除在外，同一业务意图的重复入队必须得到相同摘要。
 */
public final class OutboxSemanticKey {

    /** 不参与载荷摘要的键：调用链标识与发送时间，不属于业务意图。 */
    public static final java.util.Set<String> VOLATILE_PAYLOAD_KEYS =
            java.util.Set.of("correlationId", "traceId", "sentAt", "enqueueAt", "deliveryToken");

    private OutboxSemanticKey() {
    }

    /** 入队语义身份：字段顺序即编码顺序，null 与空串区分对待。 */
    public static String semanticKey(
            String workspaceId,
            String businessKind,
            String objectId,
            String snapshotId,
            String generation,
            String stage,
            Integer batchNo
    ) {
        StringBuilder canonical = new StringBuilder(128);
        appendSegment(canonical, workspaceId);
        appendSegment(canonical, businessKind);
        appendSegment(canonical, objectId);
        appendSegment(canonical, snapshotId);
        appendSegment(canonical, generation);
        appendSegment(canonical, stage);
        appendSegment(canonical, batchNo == null ? null : String.valueOf(batchNo));
        return sha256(canonical.toString());
    }

    /** 规范化业务载荷摘要：排序键、剔除 volatile 键、稳定 JSON 形式。 */
    public static String payloadDigest(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return sha256("");
        }
        Map<String, Object> canonical = new TreeMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getKey() == null || VOLATILE_PAYLOAD_KEYS.contains(entry.getKey())) {
                continue;
            }
            canonical.put(entry.getKey(), entry.getValue());
        }
        StringBuilder value = new StringBuilder(64);
        for (Map.Entry<String, Object> entry : canonical.entrySet()) {
            appendSegment(value, entry.getKey());
            appendSegment(value, entry.getValue() == null ? null : String.valueOf(entry.getValue()));
        }
        return sha256(value.toString());
    }

    /** 长度前缀段：{@code len:value}，len 为 UTF-8 字节长度，段与段之间自定界。 */
    private static void appendSegment(StringBuilder target, String value) {
        if (value == null) {
            target.append("n;");
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        target.append(bytes.length).append(':').append(value).append(';');
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
