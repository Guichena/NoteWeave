package com.noteweave.context;

import com.noteweave.conversation.TokenCounter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 上下文视图：契约允许的层里，本次实际选中的条目与被剔除条目的原因。
 *
 * <p>视图只描述选择结果，不做渲染。条目顺序严格按契约的渲染顺序排列，同层内按序号升序，
 * 因此视图可以直接序列化进快照，并在任意时刻确定性重建。</p>
 *
 * <p>每条被剔除的候选都带原因：缺可选材料时有原因，不静默补未来状态。
 * 视图里出现过的引用不得同时出现在剔除列表里。</p>
 */
public record ContextView(
        String schemaVersion,
        ContextContract contract,
        List<Item> items,
        List<Exclusion> exclusions,
        int estimatedTokens,
        List<String> degradationReasons
) {

    /** 视图结构版本。 */
    public static final String SCHEMA_VERSION = "context-view-v1";

    public ContextView {
        items = items == null ? List.of() : List.copyOf(items);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        degradationReasons = degradationReasons == null ? List.of() : List.copyOf(degradationReasons);
        if (!SCHEMA_VERSION.equals(schemaVersion) || contract == null) {
            throw new IllegalArgumentException("context view requires a schema version and contract");
        }
        Map<ContextLayer, Integer> layerOrder = new LinkedHashMap<>();
        for (int index = 0; index < contract.ordering().size(); index++) {
            layerOrder.put(contract.ordering().get(index), index);
        }
        Set<String> itemIds = new HashSet<>();
        Set<String> refs = new HashSet<>();
        int previousLayer = -1;
        int previousSeq = -1;
        long total = 0;
        for (Item item : items) {
            Integer layerIndex = layerOrder.get(item.layer());
            if (layerIndex == null) {
                throw new IllegalArgumentException("context view contains a layer outside the contract");
            }
            if (layerIndex < previousLayer
                    || (layerIndex == previousLayer && item.startSeq() < previousSeq)) {
                throw new IllegalArgumentException("context view items are not in contract order");
            }
            previousLayer = layerIndex;
            previousSeq = item.startSeq();
            if (!itemIds.add(item.itemId()) || !refs.add(refKey(item.refType(), item.refId(), item.layer()))) {
                throw new IllegalArgumentException("context view contains a duplicate item");
            }
            total += item.tokens();
        }
        if (total != estimatedTokens) {
            throw new IllegalArgumentException("context view token total does not match its items");
        }
        for (Exclusion exclusion : exclusions) {
            if (refs.contains(refKey(exclusion.refType(), exclusion.refId(), exclusion.layer()))) {
                throw new IllegalArgumentException("an item cannot be both included and excluded");
            }
            if (!contract.allows(exclusion.layer())) {
                throw new IllegalArgumentException("exclusion refers to a layer outside the contract");
            }
        }
        for (String reason : degradationReasons) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("degradation reasons must be recorded explicitly");
            }
        }
    }

    /** 按契约与选中条目构造视图，token 总量由条目汇总得出，不接受外部传入。 */
    public static ContextView of(ContextContract contract, List<Item> items,
                                 List<Exclusion> exclusions, List<String> degradationReasons) {
        long total = 0;
        for (Item item : items) {
            total += item.tokens();
        }
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("context view is too large");
        }
        return new ContextView(SCHEMA_VERSION, contract, items, exclusions, (int) total, degradationReasons);
    }

    /** 某层的条目，保持视图顺序。 */
    public List<Item> itemsOf(ContextLayer layer) {
        List<Item> selected = new ArrayList<>();
        for (Item item : items) {
            if (item.layer() == layer) {
                selected.add(item);
            }
        }
        return List.copyOf(selected);
    }

    /** 视图是否包含某层材料。 */
    public boolean has(ContextLayer layer) {
        return items.stream().anyMatch(item -> item.layer() == layer);
    }

    /** 视图身份的稳定摘要，用于快照比对。 */
    public String identitySha256() {
        StringBuilder builder = new StringBuilder(contract.identitySha256());
        for (Item item : items) {
            builder.append('|').append(item.itemId()).append(':').append(item.textSha256());
        }
        for (Exclusion exclusion : exclusions) {
            builder.append('|').append(exclusion.refType().wire()).append(':')
                    .append(exclusion.refId()).append(':').append(exclusion.reason());
        }
        return sha256(builder.toString());
    }

    /**
     * 视图条目。
     *
     * @param itemId     稳定条目 id，由层、形态、引用与序号派生
     * @param layer      所属层
     * @param form       材料形态，摘要与句柄不得标成原文
     * @param refType    引用对象类型
     * @param refId      引用对象身份
     * @param startSeq   覆盖起始消息序号，0 表示不覆盖消息
     * @param endSeq     覆盖结束消息序号，0 表示不覆盖消息
     * @param text       渲染正文
     * @param textSha256 正文摘要
     * @param tokens     正文的估算 token
     */
    public record Item(
            String itemId,
            ContextLayer layer,
            ContextItemForm form,
            ContextRefType refType,
            String refId,
            int startSeq,
            int endSeq,
            String text,
            String textSha256,
            int tokens
    ) {
        public Item {
            if (blank(itemId) || layer == null || form == null || refType == null || blank(refId)
                    || text == null || startSeq < 0 || endSeq < 0
                    || (endSeq != 0 && endSeq < startSeq) || tokens < 0) {
                throw new IllegalArgumentException("context item is invalid");
            }
            if (!sha256(text).equals(textSha256) || estimate(text) != tokens) {
                throw new IllegalArgumentException("context item digest or token count is inconsistent");
            }
        }

        /** 由正文派生 id、摘要与 token，调用方不得自行传入这三个值。 */
        public static Item of(ContextLayer layer, ContextItemForm form, ContextRefType refType,
                              String refId, int startSeq, int endSeq, String text) {
            String safeText = text == null ? "" : text;
            String itemId = "ctx-" + sha256(String.join("|", layer.wire(), form.wire(),
                    refType.wire(), refId, Integer.toString(startSeq), Integer.toString(endSeq)))
                    .substring(0, 24);
            return new Item(itemId, layer, form, refType, refId, startSeq, endSeq,
                    safeText, sha256(safeText), estimate(safeText));
        }

        /** 覆盖的消息条数；不覆盖消息时为 0。 */
        public int coveredMessages() {
            return startSeq == 0 || endSeq == 0 ? 0 : endSeq - startSeq + 1;
        }
    }

    /**
     * 被剔除的候选。
     *
     * @param refType 引用对象类型
     * @param refId   引用对象身份
     * @param layer   本来会进入的层
     * @param reason  稳定原因码
     * @param detail  可读补充说明，可为空串
     */
    public record Exclusion(
            ContextRefType refType,
            String refId,
            ContextLayer layer,
            String reason,
            String detail
    ) {
        public Exclusion {
            if (refType == null || blank(refId) || layer == null || blank(reason)) {
                throw new IllegalArgumentException("context exclusion requires a reference and a reason");
            }
            detail = detail == null ? "" : detail;
        }
    }

    /** 与 ContextView 一致的 token 估算入口，保证视图、渲染与快照三处计数同源。 */
    public static int estimate(String text) {
        return TokenCounter.estimate(text, null).count();
    }

    private static String refKey(ContextRefType refType, String refId, ContextLayer layer) {
        return refType.wire() + ":" + refId + ":" + layer.wire();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
