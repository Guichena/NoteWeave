package com.noteweave.conversation.constraint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 校验模型输出是否为合法的约束操作数组。
 * <p>
 * 这是一个「全有或全无」的校验器：任一必填项非法就让整批失败，绝不返回半截结果。
 * 理由是一半的 ops 比没有 ops 更危险——缺失的那一半会让调用方以为旧约束已被撤回。
 * 未知字段忽略（模型常附带解释性字段），未知的枚举值、越界的 confidence、缺失的目标一律判失败。
 * <p>
 * 失败原因只用于日志与降级判断，不写库、不回传模型。
 */
public final class ConstraintOpSchema {

    /** 单条消息最多接受的操作数：约束只涉及 9 个槽位，再多说明模型在发散。 */
    public static final int MAX_OPS = 8;
    /** 输出里找不到 JSON 数组，或数组本身被截断。 */
    public static final String NOT_JSON = "NOT_JSON";
    /** 顶层不是数组。 */
    public static final String NOT_ARRAY = "NOT_ARRAY";
    /** 数组里的元素不是对象。 */
    public static final String ITEM_NOT_OBJECT = "ITEM_NOT_OBJECT";
    public static final String TOO_MANY_OPS = "TOO_MANY_OPS";
    /** op 缺失或不在 ADD/UPDATE/REVOKE/NOOP 内。 */
    public static final String UNKNOWN_OP = "UNKNOWN_OP";
    public static final String UNKNOWN_SLOT = "UNKNOWN_SLOT";
    public static final String UNKNOWN_SCOPE = "UNKNOWN_SCOPE";
    /** ADD/UPDATE 没有槽位。 */
    public static final String MISSING_SLOT = "MISSING_SLOT";
    /** REVOKE 既没有 targetId 也没有槽位，撤回目标不确定。 */
    public static final String MISSING_TARGET = "MISSING_TARGET";
    public static final String INVALID_CONFIDENCE = "INVALID_CONFIDENCE";
    public static final String VALUE_TOO_LONG = "VALUE_TOO_LONG";

    private ConstraintOpSchema() {
    }

    /**
     * 从模型输出里解析约束操作。
     *
     * @param mapper 复用应用里的 {@link ObjectMapper}
     * @param output 模型原始输出，可能带解释性前后缀
     * @return 成功时 {@link ParseResult#ok()} 为 {@code true}；失败时 {@code ops} 为空且 {@code failure} 给出原因
     */
    public static ParseResult parse(ObjectMapper mapper, String output) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper 不能为空");
        }
        if (output == null || output.isBlank()) {
            return ParseResult.failure(NOT_JSON);
        }
        // 截取首个 [ 到末个 ]：模型经常包一层说明文字或 ```json 围栏。
        int start = output.indexOf('[');
        int end = output.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return ParseResult.failure(NOT_JSON);
        }
        JsonNode root;
        try {
            root = mapper.readTree(output.substring(start, end + 1));
        } catch (JsonProcessingException ex) {
            return ParseResult.failure(NOT_JSON);
        }
        if (root == null || !root.isArray()) {
            return ParseResult.failure(NOT_ARRAY);
        }
        if (root.size() > MAX_OPS) {
            return ParseResult.failure(TOO_MANY_OPS);
        }
        List<ConstraintOp> ops = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            ItemResult item = parseItem(node);
            if (item.failure() != null) {
                return ParseResult.failure(item.failure());
            }
            ops.add(item.op());
        }
        return new ParseResult(List.copyOf(ops), null);
    }

    private static ItemResult parseItem(JsonNode node) {
        if (node == null || !node.isObject()) {
            return ItemResult.invalid(ITEM_NOT_OBJECT);
        }
        String rawOp = text(node, "op");
        ConstraintOp.Op op = rawOp == null ? null : parseOp(rawOp);
        if (op == null) {
            return ItemResult.invalid(UNKNOWN_OP);
        }
        String rawSlot = text(node, "slot");
        ConstraintSlot slot = null;
        if (rawSlot != null) {
            slot = ConstraintSlot.fromWire(rawSlot);
            if (slot == null) {
                return ItemResult.invalid(UNKNOWN_SLOT);
            }
        }
        String rawScope = text(node, "scope");
        ConstraintScope scope = null;
        if (rawScope != null) {
            scope = ConstraintScope.fromWire(rawScope);
            if (scope == null) {
                return ItemResult.invalid(UNKNOWN_SCOPE);
            }
        }
        String value = text(node, "value");
        if (value != null && value.codePointCount(0, value.length()) > ConstraintOp.MAX_VALUE_CHARS) {
            return ItemResult.invalid(VALUE_TOO_LONG);
        }
        String targetId = text(node, "targetId");
        if (targetId == null) {
            targetId = text(node, "target_id");
        }
        double confidence = ConstraintOp.DEFAULT_CONFIDENCE;
        JsonNode confidenceNode = node.get("confidence");
        if (confidenceNode != null && !confidenceNode.isNull()) {
            if (!confidenceNode.isNumber()) {
                return ItemResult.invalid(INVALID_CONFIDENCE);
            }
            confidence = confidenceNode.asDouble();
            // 取反写法一并挡掉 NaN。
            if (!(confidence >= 0.0 && confidence <= 1.0)) {
                return ItemResult.invalid(INVALID_CONFIDENCE);
            }
        }
        if ((op == ConstraintOp.Op.ADD || op == ConstraintOp.Op.UPDATE) && slot == null) {
            return ItemResult.invalid(MISSING_SLOT);
        }
        if (op == ConstraintOp.Op.REVOKE && slot == null && targetId == null) {
            return ItemResult.invalid(MISSING_TARGET);
        }
        return new ItemResult(new ConstraintOp(op, slot, value, scope, targetId, confidence), null);
    }

    private static ConstraintOp.Op parseOp(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ConstraintOp.Op op : ConstraintOp.Op.values()) {
            if (op.name().equals(normalized)) {
                return op;
            }
        }
        return null;
    }

    /** 只接受文本字段；数字/布尔等类型一律当成缺失，避免 {@code 0.7} 之类被静默转成 slot 名。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /** 单个元素的解析结果：失败时不带 op，避免调用方拿到半成品。 */
    private record ItemResult(ConstraintOp op, String failure) {

        static ItemResult invalid(String reason) {
            return new ItemResult(null, reason);
        }
    }

    /**
     * 解析结果。
     *
     * @param ops     成功时为模型给出的操作（可能是空数组），失败时为空
     * @param failure 失败原因常量，成功时为 {@code null}
     */
    public record ParseResult(List<ConstraintOp> ops, String failure) {

        public ParseResult {
            ops = ops == null ? List.of() : List.copyOf(ops);
        }

        public boolean ok() {
            return failure == null;
        }

        public static ParseResult failure(String reason) {
            return new ParseResult(List.of(), reason);
        }
    }
}
