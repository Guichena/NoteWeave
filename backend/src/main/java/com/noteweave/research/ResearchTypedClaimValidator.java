package com.noteweave.research;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Java-side deterministic backstop for high-risk facts submitted by a Worker.
 *
 * <p>DR-303 note: the fact <em>vocabulary</em> ({@link #NUMBER}, {@link #DATE},
 * {@link #COMPARISONS}, {@link #OPPOSITES}, {@link #facts}) is package-private so the global-conflict
 * detector can reuse the exact same notion of "what counts as a number / date / direction" without
 * duplicating it. Only visibility changed — {@link #validate} (the claim-vs-own-quote containment
 * predicate) keeps its semantics untouched. The detector deliberately does <b>not</b> reuse
 * {@code validate} across two independent claims: containment is not equality.</p>
 */
@Component
class ResearchTypedClaimValidator {
    static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9_.])([+-]?\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?|[+-]?\\d+(?:\\.\\d+)?)"
                    + "\\s*(%|percent(?:age)?|百分比|个百分点|kb|mb|gb|tb|ms|毫秒|秒|分钟|小时|s\\b)?",
            Pattern.CASE_INSENSITIVE);
    static final Pattern DATE = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})(?:[-/.年](0?[1-9]|1[0-2])(?:[-/.月](0?[1-9]|[12]\\d|3[01])日?)?)?(?!\\d)");
    static final List<ComparisonPattern> COMPARISONS = List.of(
            comparison("至少|不低于|at\\s+least|no\\s+less\\s+than", "AT_LEAST"),
            comparison("至多|不高于|at\\s+most|no\\s+more\\s+than", "AT_MOST"),
            comparison("少于|低于|小于|less\\s+than|below|lower\\s+than", "LESS_THAN"),
            comparison("多于|高于|大于|more\\s+than|above|higher\\s+than", "GREATER_THAN"),
            comparison("增加|上升|增长|increase[ds]?|rose|grew", "INCREASE"),
            comparison("减少|下降|降低|decrease[ds]?|fell|declined", "DECREASE"),
            comparison("之前|以前|before|earlier\\s+than", "BEFORE"),
            comparison("之后|以后|after|later\\s+than", "AFTER"));
    static final Map<String, Set<String>> OPPOSITES = Map.of(
            "AT_LEAST", Set.of("LESS_THAN"),
            "LESS_THAN", Set.of("AT_LEAST", "GREATER_THAN"),
            "AT_MOST", Set.of("GREATER_THAN"),
            "GREATER_THAN", Set.of("AT_MOST", "LESS_THAN"),
            "INCREASE", Set.of("DECREASE"),
            "DECREASE", Set.of("INCREASE"),
            "BEFORE", Set.of("AFTER"),
            "AFTER", Set.of("BEFORE"));

    Validation validate(String claim, String quote) {
        Facts claimFacts = facts(claim);
        Facts quoteFacts = facts(quote);
        if (claimFacts.empty()) return new Validation("NOT_APPLICABLE", List.of("NO_TYPED_CLAIM_FACTS"), claimFacts.asMap(), quoteFacts.asMap());
        LinkedHashSet<String> contradictions = new LinkedHashSet<>();
        if (!claimFacts.dates().isEmpty() && !quoteFacts.dates().isEmpty()
                && !quoteFacts.dates().containsAll(claimFacts.dates())) contradictions.add("DATE_VALUE_CONTRADICTION");
        for (NumberFact expected : claimFacts.numbers()) {
            List<NumberFact> sameValue = quoteFacts.numbers().stream()
                    .filter(actual -> actual.value().equals(expected.value())).toList();
            if (!sameValue.isEmpty() && !expected.unit().isBlank()
                    && sameValue.stream().allMatch(actual -> !actual.unit().isBlank() && !actual.unit().equals(expected.unit()))) {
                contradictions.add("UNIT_CONTRADICTION");
            } else if (!quoteFacts.numbers().isEmpty() && quoteFacts.numbers().stream().noneMatch(actual -> actual.matches(expected))) {
                contradictions.add("NUMBER_VALUE_CONTRADICTION");
            }
        }
        for (String expected : claimFacts.comparisons()) {
            if (quoteFacts.comparisons().stream().anyMatch(OPPOSITES.getOrDefault(expected, Set.of())::contains)) {
                contradictions.add("COMPARISON_DIRECTION_CONTRADICTION");
            }
        }
        if (!contradictions.isEmpty()) return new Validation("CONTRADICTED", List.copyOf(contradictions), claimFacts.asMap(), quoteFacts.asMap());
        boolean missing = !quoteFacts.dates().containsAll(claimFacts.dates())
                || claimFacts.numbers().stream().anyMatch(expected -> quoteFacts.numbers().stream().noneMatch(actual -> actual.matches(expected)))
                || !quoteFacts.comparisons().containsAll(claimFacts.comparisons());
        return missing
                ? new Validation("UNKNOWN", List.of("TYPED_FACT_MISSING_FROM_CITATION_SPAN"), claimFacts.asMap(), quoteFacts.asMap())
                : new Validation("ENTAILED", List.of("TYPED_FACTS_MATCH"), claimFacts.asMap(), quoteFacts.asMap());
    }

    Facts facts(String raw) {
        String value = raw == null ? "" : raw;
        Set<String> dates = new LinkedHashSet<>();
        List<int[]> dateYearSpans = new ArrayList<>();
        Matcher dateMatcher = DATE.matcher(value);
        while (dateMatcher.find()) {
            String date = dateMatcher.group(1);
            if (dateMatcher.group(2) != null) date += "-%02d".formatted(Integer.parseInt(dateMatcher.group(2)));
            if (dateMatcher.group(3) != null) date += "-%02d".formatted(Integer.parseInt(dateMatcher.group(3)));
            dates.add(date);
            dateYearSpans.add(new int[]{dateMatcher.start(1), dateMatcher.end(1)});
        }
        List<NumberFact> numbers = new ArrayList<>();
        Matcher numberMatcher = NUMBER.matcher(value);
        while (numberMatcher.find()) {
            int start = numberMatcher.start(1);
            int end = numberMatcher.end(1);
            if (dateYearSpans.stream().anyMatch(span -> span[0] == start && span[1] == end)) continue;
            numbers.add(new NumberFact(normalizeNumber(numberMatcher.group(1)), normalizeUnit(numberMatcher.group(2))));
        }
        Set<String> comparisons = new LinkedHashSet<>();
        COMPARISONS.forEach(item -> {
            if (item.pattern().matcher(value).find()) comparisons.add(item.value());
        });
        return new Facts(List.copyOf(numbers), Set.copyOf(dates), Set.copyOf(comparisons));
    }

    private String normalizeNumber(String raw) {
        return new BigDecimal(raw.replace(",", "")).stripTrailingZeros().toPlainString();
    }

    private String normalizeUnit(String raw) {
        if (raw == null) return "";
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "%", "percent", "percentage", "百分比" -> "PERCENT";
            case "个百分点" -> "PERCENT_POINT";
            case "kb" -> "KB";
            case "mb" -> "MB";
            case "gb" -> "GB";
            case "tb" -> "TB";
            case "ms", "毫秒" -> "MS";
            case "s", "秒" -> "SECOND";
            case "分钟" -> "MINUTE";
            case "小时" -> "HOUR";
            default -> raw.toUpperCase(Locale.ROOT);
        };
    }

    private static ComparisonPattern comparison(String pattern, String value) {
        return new ComparisonPattern(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE), value);
    }

    record Validation(String status, List<String> reasonCodes,
                      Map<String, Object> claimFacts, Map<String, Object> quoteFacts) {
        Map<String, Object> asMap() {
            return Map.of("status", status, "reason_codes", reasonCodes,
                    "claim_facts", claimFacts, "quote_facts", quoteFacts);
        }
    }
    record ComparisonPattern(Pattern pattern, String value) { }
    record NumberFact(String value, String unit) {
        boolean matches(NumberFact expected) {
            return value.equals(expected.value) && (expected.unit.isBlank() || unit.equals(expected.unit));
        }
    }
    record Facts(List<NumberFact> numbers, Set<String> dates, Set<String> comparisons) {
        boolean empty() { return numbers.isEmpty() && dates.isEmpty() && comparisons.isEmpty(); }
        Map<String, Object> asMap() {
            return new LinkedHashMap<>(Map.of("numbers", numbers, "dates", dates, "comparisons", comparisons));
        }
    }
}
