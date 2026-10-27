package com.noteweave.retrieval.index;

import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import java.util.Locale;
import java.util.regex.Pattern;

public final class RetrievalIndexNames {
    private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9_-]");

    private RetrievalIndexNames() {
    }

    public static String physical(
            ProjectionType type,
            String schemaVersion,
            String workspaceId
    ) {
        return prefix(type)
                + "_v" + component(schemaVersion)
                + "_" + component(workspaceId);
    }

    public static String physical(
            ProjectionType type,
            String schemaVersion,
            String embeddingVersion,
            String workspaceId
    ) {
        return prefix(type)
                + "_v" + component(schemaVersion)
                + "_e" + component(embeddingVersion)
                + "_" + component(workspaceId);
    }

    public static String alias(ProjectionType type, String workspaceId) {
        return prefix(type) + "_" + component(workspaceId);
    }

    /**
     * 索引名是否承载指定向量版本（IDX-02）。旧命名没有 {@code _e} 版本段时返回 true，
     * 保持兼容；有版本段但与当前版本不符时返回 false，调用方必须拒绝写入或查询。
     */
    public static boolean matchesEmbeddingVersion(String indexName, String embeddingVersion) {
        if (indexName == null || embeddingVersion == null || embeddingVersion.isBlank()) {
            return true;
        }
        String normalized = indexName.toLowerCase(Locale.ROOT);
        if (!normalized.contains("_e")) {
            return true;
        }
        return normalized.contains("_e" + component(embeddingVersion) + "_");
    }

    private static String prefix(ProjectionType type) {
        return switch (type) {
            case QA_CHUNK -> "noteweave_qa_chunk";
            case NOTE_SOURCE -> "noteweave_note_source";
        };
    }

    private static String component(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = UNSAFE.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("_+", "_");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Index name component cannot be blank");
        }
        return normalized;
    }
}
