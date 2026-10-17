package com.noteweave.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 把报告里的证据引用整理成读者习惯的编号形式。
 *
 * <p>正文里的 {@code [evidence:...]} 按首次出现的顺序换成 [1]、[2]；原先分散的引用核对、Citation audit、
 * 补充引用几节合并为末尾一节「参考来源」，每条给出标题、网址和原文，保留证据编号便于追溯，
 * 不再展示内部快照路径。正文没有内联引用的模板报告只去掉快照路径行。</p>
 */
final class ResearchReportCitations {
    private static final Pattern INLINE = Pattern.compile("\\[(evidence:[0-9a-f]{16,64})]");
    private static final List<String> AUDIT_HEADINGS = List.of("## 引用核对", "## Citation audit", "## 补充引用");
    private static final int MAX_QUOTE_CHARS = 300;

    private ResearchReportCitations() {
    }

    static String renumber(JdbcTemplate jdbcTemplate, String runId, String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;
        Map<String, Integer> numbers = new LinkedHashMap<>();
        Matcher matcher = INLINE.matcher(markdown);
        while (matcher.find()) numbers.putIfAbsent(matcher.group(1), numbers.size() + 1);
        if (numbers.isEmpty()) return withoutSnapshotLines(markdown);

        StringBuilder body = new StringBuilder();
        matcher = INLINE.matcher(withoutSections(markdown));
        while (matcher.find()) {
            matcher.appendReplacement(body, Matcher.quoteReplacement("[" + numbers.get(matcher.group(1)) + "]"));
        }
        matcher.appendTail(body);
        // 相邻的 [1] [2] 合并成 [1][2]
        String text = body.toString().replaceAll("](\\s)+\\[(?=\\d+])", "][");

        StringBuilder references = new StringBuilder("\n\n## 参考来源\n\n");
        for (Map.Entry<String, Integer> entry : numbers.entrySet()) {
            List<String[]> rows = jdbcTemplate.query("""
                    select source_title, source_url, quote_text from source_evidence
                    where research_run_id = ? and evidence_key = ?
                    """, (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
                    runId, entry.getKey());
            String[] row = rows.isEmpty() ? new String[] {"", "", ""} : rows.get(0);
            String title = blankToDefault(row[0], "来源");
            String url = row[1] == null ? "" : row[1].strip();
            references.append(entry.getValue()).append(". ")
                    .append(url.isEmpty() ? oneLine(title) : "[" + oneLine(title).replace("]", "\\]") + "](" + url + ")")
                    .append("  `").append(entry.getKey()).append("`\n");
            String quote = row[2] == null ? "" : oneLine(row[2]);
            if (!quote.isEmpty()) {
                references.append("   > ").append(quote.length() > MAX_QUOTE_CHARS
                        ? quote.substring(0, MAX_QUOTE_CHARS) + "…" : quote).append("\n");
            }
        }
        return text.stripTrailing() + references;
    }

    /** 去掉引用核对类小节：从这些标题开始，到下一个二级标题为止。 */
    private static String withoutSections(String markdown) {
        List<String> kept = new ArrayList<>();
        boolean skipping = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("## ")) skipping = AUDIT_HEADINGS.stream().anyMatch(line::startsWith);
            if (!skipping) kept.add(line);
        }
        return String.join("\n", kept);
    }

    private static String withoutSnapshotLines(String markdown) {
        return markdown.replaceAll("(?m)^\\s*- Snapshot: `[^`]*`\\n", "");
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
