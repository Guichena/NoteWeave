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
