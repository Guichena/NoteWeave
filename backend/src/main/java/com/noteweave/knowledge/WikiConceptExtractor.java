package com.noteweave.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts stable Wiki navigation concepts without knowing persistence or ingest state. */
final class WikiConceptExtractor {
    private static final Pattern TAG_PATTERN = Pattern.compile("\"([^\"]{2,40})\"");
    private static final Set<String> FORMAT_CONCEPTS = Set.of(
            "markdown", "pdf", "txt", "text", "doc", "docx", "md");

    private WikiConceptExtractor() {
    }

    static List<String> extract(String title, String tagsJson) {
        List<String> concepts = new ArrayList<>();
        Matcher matcher = TAG_PATTERN.matcher(tagsJson == null ? "" : tagsJson);
        while (matcher.find() && concepts.size() < 6) {
            addConcept(concepts, matcher.group(1));
        }
        String normalizedTitle = title == null ? "" : title.replaceAll("\\.[a-zA-Z0-9]{1,8}$", "");
        for (String part : normalizedTitle.split("[\\s_\\-]+")) {
            if (concepts.size() >= 6) {
                break;
            }
            addConcept(concepts, part);
        }
        return List.copyOf(concepts);
    }

    private static void addConcept(List<String> concepts, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() < 2 || normalized.length() > 40) {
            return;
        }
        String lowered = normalized.toLowerCase(Locale.ROOT);
        if (FORMAT_CONCEPTS.contains(lowered)
                || concepts.stream().anyMatch(item -> item.equalsIgnoreCase(normalized))) {
            return;
        }
        concepts.add(normalized);
    }
}
