package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 从冻结的研究范围和服务器归档来源判断明显的版本、分支冲突，不猜测未知的发布日期。 */
final class ResearchSourceScopePolicy {
    private static final Pattern VERSION = Pattern.compile("(?<![0-9.])([0-9]{1,3}(?:\\.[0-9]+)*)(?![a-zA-Z0-9.])");
    private static final Set<String> DOC_DIRS = Set.of("docs", "doc", "documentation", "manual", "refman", "reference", "javase", "pdf", "version", "versions", "release", "releases", "whatsnew", "release-notes", "changelog");
    private static final Set<String> DEVELOPMENT = Set.of("dev", "development", "nightly", "main", "master", "next");
    private static final Set<String> CURRENT = Set.of("current", "stable", "latest");
    private static final Pattern CURRENT_REQUEST = Pattern.compile("目前|当前|现阶段|现今|最新|\\b(?:current|currently|latest|today|now)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORY_REQUEST = Pattern.compile("首次|最初|引入|历史|何时|开始|起始|哪个版本|哪一版|哪一个版本|\\b(?:introduced|initial|historical|first|when|since|starting|started|added\\s+in)\\b", Pattern.CASE_INSENSITIVE);

    static Map<String, Object> freeze(String cell, String entity, String question, String asOf) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("cell_key", cell); scope.put("entity_label", entity); scope.put("required_versions", versions(entity));
        scope.put("as_of_date", asOf); scope.put("temporal_mode", temporalMode(question));
        scope.put("allow_development", Pattern.compile("开发版|预览版|\\b(?:nightly|development|preview|dev)\\b", Pattern.CASE_INSENSITIVE).matcher(entity).find());
        scope.put("source_preference", Pattern.compile("原始研究|实证|实验结果|基准测试|论文|\\b(?:empirical|benchmark|experiment|study|studies|paper)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(question).find() ? "ORIGINAL_RESEARCH" : "PRIMARY_SOURCE");
        return Map.copyOf(scope);
    }

    static String temporalMode(String text) {
        if (CURRENT_REQUEST.matcher(text).find()) return "CURRENT";
        return HISTORY_REQUEST.matcher(text).find() ? "HISTORICAL" : "UNSPECIFIED";
    }

    /** 单元格先按自己的需求文本判断；题目同时问历史和当前时不再整体继承，避免首次提供的版本也被当成当前状态核验。 */
    static String cellTemporalMode(String question, String cellText) {
        String own = temporalMode(cellText);
        if (!"UNSPECIFIED".equals(own)) return own;
        boolean mixed = CURRENT_REQUEST.matcher(question).find() && HISTORY_REQUEST.matcher(question).find();
        return mixed ? "UNSPECIFIED" : temporalMode(question);
    }

    static String cutoffDate(String question, String startedDate) {
        var match = Pattern.compile("(?:截至|截止(?:到)?|as\\s+of|until|through|before)\\s*[:：]?\\s*(\\d{4})(?:[-年/](\\d{1,2})(?:[-月/](\\d{1,2})日?)?月?)?年?",Pattern.CASE_INSENSITIVE).matcher(question);
        if (!match.find()) return startedDate;
        try {
            int year = Integer.parseInt(match.group(1));
            var period = java.time.YearMonth.of(year,match.group(2) == null ? 12 : Integer.parseInt(match.group(2)));
            var requested = match.group(3) == null ? period.atEndOfMonth() : period.atDay(Integer.parseInt(match.group(3)));
            if (match.group().toLowerCase(Locale.ROOT).startsWith("before")) requested = match.group(3) != null ? requested.minusDays(1)
                    : match.group(2) != null ? period.atDay(1).minusDays(1) : java.time.LocalDate.of(year,1,1).minusDays(1);
            var started = java.time.LocalDate.parse(startedDate);
            return requested.isAfter(started) ? startedDate : requested.toString();
        } catch (RuntimeException error) { return startedDate; }
    }

    static List<String> versions(String label) {
        String text = label.replaceAll("(?i)\\b(?:RFC|PEP|JEP|CVE|ISO|IEC)\\s*[-:]?\\s*\\d+(?:[-.:]\\d+)*", "")
                .replaceAll("(?i)(?:19|20)\\d{2}\\s*(?:edition|年版|版本|版)|\\bedition\\s+(?:19|20)\\d{2}", "");
        Set<String> found = new LinkedHashSet<>();
        VERSION.matcher(text).results().forEach(match -> found.add(match.group(1)));
        return List.copyOf(found);
    }

    static JsonNode scope(JsonNode contract, String cell) {
        for (JsonNode item : contract.path("source_scopes")) if (cell.equals(item.path("cell_key").asText())) return item;
        return new ObjectMapper().createObjectNode();
    }

    static Map<String, Object> evaluate(JsonNode scope, String url, String title, String quote) {
        return evaluate(scope, url, title, quote, "");
    }

    static Map<String, Object> evaluate(JsonNode scope, String url, String title, String quote, String context) {
        if (scope.isEmpty()) return Map.of("reason_codes", List.of(), "temporal_status", "UNKNOWN");
        String path;
        try { path = URLDecoder.decode(URI.create(url == null ? "" : url).getRawPath(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT); }
        catch (Exception error) { path = ""; }
        List<String> segments = java.util.Arrays.stream(path.split("/")).filter(item -> !item.isBlank()).toList();
        Set<String> found = new LinkedHashSet<>();
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i).replaceFirst("^v(?=\\d)", "");
            if (i > 0 && DOC_DIRS.contains(segments.get(i - 1))) segment = segment.replaceFirst("\\.(?:html?|md|rst)$", "");
            if (segment.matches("\\d{1,3}\\.\\d+(?:\\.\\d+)*")) found.add(segment);
            else if (i > 0 && DOC_DIRS.contains(segments.get(i - 1)) && segment.matches("\\d{1,3}")) found.add(segment);
        }
        List<String> required = new ArrayList<>();
        scope.path("required_versions").forEach(value -> required.add(value.asText()));
        String versionStatus = required.isEmpty() ? "NOT_REQUIRED" : "UNKNOWN";
        boolean exact = required.stream().anyMatch(target -> found.stream().anyMatch(value -> value.equals(target) || value.startsWith(target + ".")));
        boolean coarse = required.stream().anyMatch(target -> found.stream().anyMatch(value -> target.startsWith(value + ".")));
        List<String> anchors = versions((title == null ? "" : title) + " " + (quote == null ? "" : quote) + " " + (context == null ? "" : context));
        boolean anchor = required.stream().anyMatch(target -> anchors.stream().anyMatch(value -> value.equals(target) || value.startsWith(target + ".")));
        if (!required.isEmpty()) {
            if (exact || (found.isEmpty() || coarse) && anchor) versionStatus = "MATCH";
            else if (!found.isEmpty() && !coarse) versionStatus = "MISMATCH";
        }
        boolean development = segments.stream().anyMatch(DEVELOPMENT::contains);
        String temporal = development ? "DEVELOPMENT" : segments.stream().anyMatch(CURRENT::contains) ? "CURRENT_ALIAS"
                : !found.isEmpty() ? "VERSIONED_SOURCE" : "UNKNOWN";
        List<String> reasons = new ArrayList<>();
        if ("MISMATCH".equals(versionStatus)) reasons.add("SOURCE_VERSION_MISMATCH");
        if ("UNKNOWN".equals(versionStatus)) reasons.add("SOURCE_VERSION_UNRESOLVED");
        if (development && !scope.path("allow_development").asBoolean()) reasons.add("SOURCE_DEVELOPMENT_BRANCH_MISMATCH");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reason_codes", reasons); result.put("version_status", versionStatus); result.put("temporal_status", temporal);
        result.put("document_versions", List.copyOf(found)); result.put("required_versions", required);
        result.put("source_url", url == null ? "" : url); result.put("as_of_date", scope.path("as_of_date").asText());
        result.put("freshness_status", "UNKNOWN");
        return Map.copyOf(result);
    }

    static List<String> reasons(JsonNode scope, String url, String title, String quote) {
        return ((List<?>) evaluate(scope, url, title, quote).get("reason_codes")).stream().map(String::valueOf).toList();
    }

    static JsonNode contract(String context, ObjectMapper mapper) {
        if (context == null || context.isBlank()) return mapper.createObjectNode();
        try {
            JsonNode node = mapper.readTree(context);
            if (node.isTextual()) node = mapper.readTree(node.asText());
            return node.path("query_policy").path("requirement_contract");
        } catch (Exception error) { throw new IllegalStateException("来源范围契约无法读取", error); }
    }
}
