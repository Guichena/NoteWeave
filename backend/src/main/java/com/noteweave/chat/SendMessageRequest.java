package com.noteweave.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;

public record SendMessageRequest(
        @NotBlank @Size(max = 8000) String content,
        @NotBlank @Size(max = 32) String answerMode,
        @NotBlank @Size(max = 120) String clientRequestId,
        @Size(max = 50) List<@NotBlank @Size(max = 36) String> sourceScopeSourceIds,
        @Size(max = 36) String expectedHistoryHeadMessageId,
        @Size(max = 16) String retrievalStrategy,
        @Size(max = 3) List<@NotBlank @Size(max = 16) String> retrievalChannels,
        @Size(max = 50) List<@NotBlank @Size(max = 120) String> groundingRefs
) {
    public SendMessageRequest {
        answerMode = answerMode == null
                ? null : answerMode.trim().toUpperCase(Locale.ROOT);
        sourceScopeSourceIds = sourceScopeSourceIds == null
                ? List.of()
                : sourceScopeSourceIds.stream()
                        .map(sourceId -> sourceId == null ? null : sourceId.trim())
                        .distinct()
                        .toList();
        expectedHistoryHeadMessageId = expectedHistoryHeadMessageId == null
                ? null : expectedHistoryHeadMessageId.trim();
        if (expectedHistoryHeadMessageId != null && expectedHistoryHeadMessageId.isBlank()) {
            expectedHistoryHeadMessageId = null;
        }
        retrievalStrategy = retrievalStrategy == null || retrievalStrategy.isBlank()
                ? null : retrievalStrategy.trim().toUpperCase(Locale.ROOT);
        retrievalChannels = normalizeChannels(retrievalChannels);
        groundingRefs = normalizeRefs(groundingRefs);
    }

    public SendMessageRequest(
            String content,
            String answerMode,
            String clientRequestId,
            List<String> sourceScopeSourceIds
    ) {
        this(content, answerMode, clientRequestId, sourceScopeSourceIds, null, null, List.of(), List.of());
    }

    public SendMessageRequest(
            String content,
            String answerMode,
            String clientRequestId,
            List<String> sourceScopeSourceIds,
            String expectedHistoryHeadMessageId
    ) {
        this(content, answerMode, clientRequestId, sourceScopeSourceIds, expectedHistoryHeadMessageId,
                null, List.of(), List.of());
    }

    private static List<String> normalizeRefs(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> value == null ? null : value.trim())
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private static List<String> normalizeChannels(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(value -> value == null ? null : value.trim())
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
