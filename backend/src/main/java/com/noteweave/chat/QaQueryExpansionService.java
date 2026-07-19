package com.noteweave.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Local low-recall query expansion aligned with WeKnora's deterministic fallback. */
@Component
public class QaQueryExpansionService {
    private static final int MAX_EXPANSIONS = 5;
    private static final String CURRENT_PREFIX = "\u5f53\u524d\u95ee\u9898\uff1a";
    private static final String ANCHOR_PREFIX = "\u4e3b\u9898\u951a\u70b9\uff1a";
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern QUOTED = Pattern.compile(
            "[\\\"'\\u201c\\u201d\\u300c\\u300d\\u300e\\u300f]([^\\\"'\\u201c\\u201d\\u300c\\u300d\\u300e\\u300f]+)[\\\"'\\u201c\\u201d\\u300c\\u300d\\u300e\\u300f]");
    private static final Pattern DELIMITER = Pattern.compile("[,;:!?\\s\\u3001\\u3002\\uff0c\\uff1b\\uff1a\\uff01\\uff1f]+");
    private static final Pattern QUESTION_PREFIX = Pattern.compile(
            "(?i)^(?:what\\s+is|how\\s+(?:do|does|can|to)|why\\s+is|which|where|when|who|"
                    + "\\u4ec0\\u4e48\\u662f|\\u4ec0\\u4e48|\\u5982\\u4f55|\\u600e\\u4e48|\\u4e3a\\u4ec0\\u4e48|"
                    + "\\u54ea\\u4e2a|\\u54ea\\u4e9b|\\u8bf7\\u95ee|\\u8bf7\\u544a\\u8bc9\\u6211|\\u5e2e\\u6211|"
                    + "\\u6211\\u60f3\\u77e5\\u9053|\\u6211\\u60f3\\u4e86\\u89e3)\\s*");
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "can", "to", "of", "in", "for", "on",
            "with", "at", "by", "from", "as", "into", "through", "about", "what", "how",
            "why", "when", "where", "which", "who", "whom", "whose");

    public List<String> expand(String rawQuery) {
        QueryParts parts = parse(rawQuery);
        if (parts.query().isBlank()) return List.of();

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        remember(seen, text(rawQuery));
        remember(seen, parts.query());
        List<String> expansions = new ArrayList<>();

        if (!parts.anchor().isBlank()) add(expansions, seen, parts.anchor() + " " + parts.query());

        List<String> keywords = tokens(parts.query()).stream()
                .filter(token -> token.length() > 1)
                .filter(token -> !STOPWORDS.contains(token.toLowerCase(Locale.ROOT)))
                .toList();
        if (keywords.size() >= 2) add(expansions, seen, String.join(" ", keywords));

        Matcher quoted = QUOTED.matcher(parts.query());
        while (quoted.find()) add(expansions, seen, quoted.group(1));

        for (String segment : DELIMITER.split(parts.query())) {
            if (segment.strip().length() > 5) add(expansions, seen, segment);
        }

        add(expansions, seen, QUESTION_PREFIX.matcher(parts.query()).replaceFirst("").strip());
        return expansions.stream().limit(MAX_EXPANSIONS).toList();
    }

    private QueryParts parse(String rawQuery) {
        String query = text(rawQuery);
        String current = "";
        String anchor = "";
        for (String line : query.split("\\R")) {
            String value = line.strip();
            if (value.startsWith(CURRENT_PREFIX)) current = value.substring(CURRENT_PREFIX.length()).strip();
            if (value.startsWith(ANCHOR_PREFIX)) anchor = value.substring(ANCHOR_PREFIX.length()).strip();
        }
        return new QueryParts(current.isBlank() ? query : current, anchor);
    }

    private List<String> tokens(String value) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(value);
        while (matcher.find()) tokens.add(matcher.group());
        return tokens;
    }

    private void add(List<String> expansions, Set<String> seen, String candidate) {
        String value = text(candidate);
        if (value.length() < 3 || !seen.add(value.toLowerCase(Locale.ROOT))) return;
        expansions.add(value);
    }

    private void remember(Set<String> seen, String value) {
        if (!value.isBlank()) seen.add(value.toLowerCase(Locale.ROOT));
    }

    private String text(String value) {
        return value == null ? "" : value.strip();
    }

    private record QueryParts(String query, String anchor) { }
}
