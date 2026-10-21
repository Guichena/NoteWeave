package com.noteweave.conversation.constraint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 约束行的稳定 id。
 * <p>
 * id 只依赖 {@code (messageId, slot, index)}：重投影同一条消息时得到同一个 id，
 * 因此 {@code superseded_by} 与「重放对账」都可用 id 比较，不依赖自增或随机值。
 * 拼接结果 {@code "constraint-" + 24 位十六进制} 共 35 字符，容得下
 * {@code conversation_constraint_v2.id varchar(36)}。
 */
public final class ConstraintIds {

    /** 与规则路径同一个前缀，方便按 id 分辨约束行。 */
    static final String PREFIX = "constraint-";
    /** varchar(36) 减去前缀后留一位余量，取 24 位十六进制（96 bit）足以避免同一会话内撞号。 */
    private static final int DIGEST_CHARS = 24;

    private ConstraintIds() {
    }

    /**
     * 生成一条约束行的 id。
     *
     * @param messageId 源消息 id，不能为空
     * @param slot      槽位字面量（{@link ConstraintSlot#wire()}），不能为空
     * @param index     操作在该消息 ops 数组里的下标，用来区分同一条消息落到同一槽位的多条约束
     * @return 稳定的 35 字符 id
     */
    public static String of(String messageId, String slot, int index) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(slot, "slot");
        if (index < 0) {
            throw new IllegalArgumentException("index 不能为负");
        }
        return PREFIX + digest(messageId + ":" + slot + ":" + index).substring(0, DIGEST_CHARS);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
