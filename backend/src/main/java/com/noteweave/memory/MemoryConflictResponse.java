package com.noteweave.memory;

/** 记忆列表和复核队列里的冲突摘要。 */
public record MemoryConflictResponse(
        String type,
        String conflictingItemId,
        String conflictingText
) {
    public MemoryConflictResponse {
        type = type == null || type.isBlank() ? "NONE" : type;
        conflictingItemId = conflictingItemId == null ? "" : conflictingItemId;
        conflictingText = conflictingText == null ? "" : conflictingText;
    }

    public static MemoryConflictResponse from(
            String type,
            String conflictingItemId,
            String conflictingText,
            String legacyStatus
    ) {
        String resolved = type == null ? "" : type.trim();
        if (resolved.isBlank()) {
            resolved = switch (legacyStatus == null ? "" : legacyStatus) {
                case "CONFLICT_CHECK_NOT_RUN" -> "CHECK_NOT_RUN";
                case "CONFLICTING_ACTIVE_MEMORY" -> "SEMANTIC";
                default -> "NONE";
            };
        }
        if ("NONE".equals(resolved)) {
            return new MemoryConflictResponse("NONE", "", "");
        }
        return new MemoryConflictResponse(resolved, conflictingItemId, conflictingText);
    }
}
