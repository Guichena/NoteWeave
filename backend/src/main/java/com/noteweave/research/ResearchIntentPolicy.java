package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.blankIfNull;

import java.util.List;

/** Canonical normalization rules shared by Research command and read models. */
final class ResearchIntentPolicy {

    private ResearchIntentPolicy() {
    }

    static String normalizeProfile(String value) {
        return normalizeToken(value);
    }

    static ResearchIntentResponse normalize(CreateResearchRunRequest request) {
        return normalize(new ResearchIntentResponse(
                blankIfNull(blankToNull(request.researchGoal())),
                blankIfNull(blankToNull(request.deliverableFormat())),
                normalizeConstraints(request.constraints()),
                blankIfNull(blankToNull(request.timeRange())),
                defaultIfBlank(normalizeToken(request.depth()), "STANDARD"),
                defaultIfBlank(normalizeToken(request.researchType()), "AUTO")
        ));
    }

    static ResearchIntentResponse normalize(ResearchIntentResponse intent) {
        if (intent == null) {
            return defaultIntent();
        }
        return new ResearchIntentResponse(
                blankIfNull(blankToNull(intent.researchGoal())),
                blankIfNull(blankToNull(intent.deliverableFormat())),
                normalizeConstraints(intent.constraints()),
                blankIfNull(blankToNull(intent.timeRange())),
                defaultIfBlank(normalizeToken(intent.depth()), "STANDARD"),
                defaultIfBlank(normalizeToken(intent.researchType()), "AUTO")
        );
    }

    static ResearchIntentResponse defaultIntent() {
        return new ResearchIntentResponse("", "", List.of(), "", "STANDARD", "AUTO");
    }

    private static List<String> normalizeConstraints(List<String> constraints) {
        if (constraints == null || constraints.isEmpty()) {
            return List.of();
        }
        return constraints.stream()
                .map(ResearchIntentPolicy::blankToNull)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private static String normalizeToken(String value) {
        return value == null
                ? ""
                : value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
