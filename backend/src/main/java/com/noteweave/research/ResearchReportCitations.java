package com.noteweave.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 整理报告末尾的引用核对。
 *
 * <p>正文里的 {@code [evidence:...]} 保持原样，前端按首次出现的顺序把它显示成可点击的编号角标；
 * 原先分散的引用核对、Citation audit、补充引用几节合并为末尾一节 Citation audit（前端会把它收进可折叠的审计区），
 * 条目编号与角标一致，每条给出标题、原文摘录和网址，不再展示内部快照路径。
 * 正文没有内联引用的模板报告只去掉快照路径行。</p>
 */
final class ResearchReportCitations {
    private static final Pattern INLINE = Pattern.compile("\\[(evidence:[0-9a-f]{16,64})]");
    private static final List<String> AUDIT_HEADINGS = List.of("## 引用核对", "## Citation audit", "## 补充引用");
    private static final int MAX_QUOTE_CHARS = 300;

    private ResearchReportCitations() {
    }

    static String consolidate(JdbcTemplate jdbcTemplate, String runId, String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;
        Map<String, Integer> numbers = new LinkedHashMap<>();
        Matcher matcher = INLINE.matcher(markdown);
        while (matcher.find()) numbers.putIfAbsent(matcher.group(1), numbers.size() + 1);
        if (numbers.isEmpty()) return withoutSnapshotLines(markdown);

        StringBuilder audit = new StringBuilder("\n\n## Citation audit\n\n");
        for (Map.Entry<String, Integer> entry : numbers.entrySet()) {
            List<String[]> rows = jdbcTemplate.query("""
                    select source_title, source_url, quote_text from source_evidence
                    where research_run_id = ? and evidence_key = ?
                    """, (rs, rowNum) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
                    runId, entry.getKey());
            String[] row = rows.isEmpty() ? new String[] {"", "", ""} : rows.get(0);
            String title = row[0] == null || row[0].isBlank() ? "来源" : oneLine(row[0]);
            String url = row[1] == null ? "" : row[1].strip();
            String quote = oneLine(row[2]);
            audit.append("### [").append(entry.getValue()).append("] ").append(title).append("\n\n")
                    .append("- Evidence: `").append(entry.getKey()).append("`\n");
            if (!quote.isEmpty()) {
                audit.append("  - Exact quote: “")
                        .append(quote.length() > MAX_QUOTE_CHARS ? quote.substring(0, MAX_QUOTE_CHARS) + "…" : quote)
                        .append("”\n");
            }
            if (!url.isEmpty()) audit.append("  - URL: ").append(url).append("\n");
            audit.append("\n");
        }
        // 模板报告的已验证结论里也有快照路径行，未经核实的线索带来内联引用后同样要去掉
        return withoutSnapshotLines(withoutSections(markdown)).stripTrailing() + audit.toString().stripTrailing() + "\n";
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
}
