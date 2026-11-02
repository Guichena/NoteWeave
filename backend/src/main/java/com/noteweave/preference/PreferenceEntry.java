package com.noteweave.preference;

import java.util.Locale;

/**
 * F5-01：一条候选偏好。它可能是本轮要求、会话/话题要求或长期记忆。
 *
 * @param targetKey  细粒度语义目标键，同一键才会互相覆盖
 * @param family     语义维度族，仅用于归类
 * @param value      可读取取值；否定项可为空
 * @param polarity   REQUIRE / FORBID
 * @param layer      本轮 / 会话话题 / 长期
 * @param scopeOwner USER 或 WORKSPACE
 * @param scopeKey   作用域键（用户或工作台 + 话题/会话）
 * @param sourceId   来源身份（消息、约束行或记忆条目的稳定 id）
 * @param versionId  来源版本，可为空串
 * @param sequence   同层内用于稳定决胜的序号，越大越新
 */
public record PreferenceEntry(
        String targetKey,
        PreferenceSlotFamily family,
        String value,
        String polarity,
        PreferenceLayer layer,
        String scopeOwner,
        String scopeKey,
        String sourceId,
        String versionId,
        long sequence
) {

    public PreferenceEntry {
        if (targetKey == null || targetKey.isBlank() || family == null || layer == null) {
            throw new IllegalArgumentException("preference entry requires target key, family and layer");
        }
        targetKey = targetKey.trim();
        polarity = polarity == null || polarity.isBlank()
                ? "REQUIRE" : polarity.trim().toUpperCase(Locale.ROOT);
        if (!"REQUIRE".equals(polarity) && !"FORBID".equals(polarity)) {
            throw new IllegalArgumentException("preference polarity must be REQUIRE or FORBID");
        }
        scopeOwner = scopeOwner == null || scopeOwner.isBlank()
                ? "WORKSPACE" : scopeOwner.trim().toUpperCase(Locale.ROOT);
        scopeKey = scopeKey == null ? "" : scopeKey;
        sourceId = sourceId == null ? "" : sourceId;
        versionId = versionId == null ? "" : versionId;
        if (sequence < 0) {
            throw new IllegalArgumentException("preference sequence cannot be negative");
        }
    }

    public boolean forbids() {
        return "FORBID".equals(polarity);
    }

    /** 同层同键的稳定排序：用户作用域优先，其次较新的序号。 */
    public int sameLayerRank() {
        int ownerRank = "USER".equals(scopeOwner) ? 0 : 1;
        return ownerRank;
    }
}
