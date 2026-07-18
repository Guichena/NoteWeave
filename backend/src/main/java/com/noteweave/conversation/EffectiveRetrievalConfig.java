package com.noteweave.conversation;

import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.Locale;

public record EffectiveRetrievalConfig(
        String strategy,
        List<String> channels,
        List<String> sourceScope,
        List<String> groundingRefs
) {
    private static final List<String> ALLOWED_STRATEGIES = List.of("NONE", "AUTO", "EXPLICIT");
    private static final List<String> ALLOWED_CHANNELS = List.of("WORKSPACE", "REPORT", "WEB");

    public EffectiveRetrievalConfig {
        channels = List.copyOf(channels);
        sourceScope = List.copyOf(sourceScope);
        groundingRefs = List.copyOf(groundingRefs);
    }

    public static EffectiveRetrievalConfig resolve(
            String requestedStrategy,
            List<String> requestedChannels,
            List<String> sourceScope,
            List<String> groundingRefs
    ) {
        List<String> normalizedScope = normalizeReferences(sourceScope);
        List<String> normalizedGrounding = normalizeReferences(groundingRefs);
        List<String> normalizedChannels = normalizeChannels(requestedChannels);
        String strategy = normalizeValue(requestedStrategy);
        if (strategy == null) {
            strategy = normalizedScope.isEmpty() && normalizedGrounding.isEmpty() ? "AUTO" : "EXPLICIT";
            normalizedChannels = List.of("WORKSPACE");
        }
        if (!ALLOWED_STRATEGIES.contains(strategy)
                || normalizedChannels.stream().anyMatch(channel -> !ALLOWED_CHANNELS.contains(channel))) {
            throw invalid("strategy or channel is unsupported");
        }
        if ("NONE".equals(strategy)) {
            if (!normalizedChannels.isEmpty() || !normalizedScope.isEmpty() || !normalizedGrounding.isEmpty()) {
                throw invalid("NONE cannot carry channels, source scope, or grounding refs");
            }
        } else if ("AUTO".equals(strategy)) {
            if (normalizedChannels.isEmpty()) {
                throw invalid("AUTO requires at least one retrieval channel");
            }
            requireChannel(normalizedScope, normalizedChannels, "WORKSPACE", "source scope");
            requireChannel(normalizedGrounding, normalizedChannels, "REPORT", "grounding refs");
        } else {
            if (normalizedScope.isEmpty() && normalizedGrounding.isEmpty()) {
                throw invalid("EXPLICIT requires source scope or grounding refs");
            }
            requireChannel(normalizedScope, normalizedChannels, "WORKSPACE", "source scope");
            requireChannel(normalizedGrounding, normalizedChannels, "REPORT", "grounding refs");
        }
        return new EffectiveRetrievalConfig(strategy, normalizedChannels, normalizedScope, normalizedGrounding);
    }

    private static void requireChannel(
            List<String> values,
            List<String> channels,
            String expectedChannel,
            String valueName
    ) {
        if (!values.isEmpty() && !channels.contains(expectedChannel)) {
            throw invalid(valueName + " requires the " + expectedChannel + " channel");
        }
    }

    private static List<String> normalizeReferences(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static List<String> normalizeChannels(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(EffectiveRetrievalConfig::normalizeValue)
                .distinct()
                .toList();
    }

    private static String normalizeValue(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException("RETRIEVAL_CONFIG_INVALID", "RetrievalConfig is invalid: " + reason);
    }
}
