package com.noteweave.research;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 报告末尾的引用核对合并为一节，正文证据标记保持原样。 */
class ResearchReportCitationsTest {

    private static final String A = "evidence:" + "a".repeat(48);
    private static final String B = "evidence:" + "b".repeat(48);

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void scatteredAuditSectionsMergeIntoOneNumberedCitationAudit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("run-1"), eq(A))).thenReturn((List) rows("JEP 444", "https://openjdk.org/jeps/444", "Virtual threads are final in JDK 21."));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("run-1"), eq(B))).thenReturn((List) rows("JEP 491", "https://openjdk.org/jeps/491", "Release 24"));
        String markdown = "# 标题\n\n虚拟线程在 JDK 21 正式发布 [" + A + "] [" + B + "]。再次引用 [" + A + "]。\n\n"
                + "## 引用核对\n\n- Evidence: `" + A + "`\n  - Snapshot: `research/external/x`\n\n"
                + "## 局限\n\n- 无\n\n## 补充引用\n\n- Evidence: `" + B + "`\n";

        String result = ResearchReportCitations.consolidate(jdbc, "run-1", markdown);

        // 正文的证据标记原样保留，由前端显示成可点击的编号角标
        assertThat(result).contains("虚拟线程在 JDK 21 正式发布 [" + A + "] [" + B + "]。再次引用 [" + A + "]。", "## 局限");
        // 审计合并为末尾唯一一节，编号与角标的首次出现顺序一致
        assertThat(result).endsWith("## Citation audit\n\n"
                + "### [1] JEP 444\n\n- Evidence: `" + A + "`\n  - Exact quote: “Virtual threads are final in JDK 21.”\n"
                + "  - URL: https://openjdk.org/jeps/444\n\n"
                + "### [2] JEP 491\n\n- Evidence: `" + B + "`\n  - Exact quote: “Release 24”\n"
                + "  - URL: https://openjdk.org/jeps/491\n");
        assertThat(result.indexOf("## 局限")).isLessThan(result.indexOf("## Citation audit"));
        assertThat(result).doesNotContain("## 引用核对", "## 补充引用", "research/external/");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void extendedQuotesAreShownInFullUpToTheWorkerExtensionLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String list = "Milvus supports the following two reranking strategies: " + "WeightedRanker merges scores. ".repeat(18)
                + "RRFRanker uses reciprocal rank fusion.";
        when(jdbc.query(anyString(), any(RowMapper.class), eq("run-1"), eq(A))).thenReturn((List) rows("Rerank", "https://milvus.io/r", list));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("run-1"), eq(B))).thenReturn((List) rows("Long", "https://e.x", "x".repeat(800)));

        String result = ResearchReportCitations.consolidate(jdbc, "run-1", "Milvus 支持两种重排策略 [" + A + "] [" + B + "]。");

        // 600 字符左右的扩展引用完整显示，列表末项仍在；超过 700 字符才截断
        assertThat(list.length()).isBetween(300, 700);
        assertThat(result).contains("RRFRanker uses reciprocal rank fusion.”", "“" + "x".repeat(700) + "…”");
    }

    @Test
    void templateReportWithoutInlineCitationsOnlyDropsSnapshotLines() {
        String markdown = "## 已验证结论\n\n- Evidence: `" + A + "`\n  - Exact quote: “q”\n  - Snapshot: `research/external/x`\n  - URL: https://e.x\n";

        String result = ResearchReportCitations.consolidate(mock(JdbcTemplate.class), "run-1", markdown);

        assertThat(result).contains("Exact quote", "URL: https://e.x").doesNotContain("Snapshot:");
    }

    private static List<String[]> rows(String title, String url, String quote) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {title, url, quote});
        return rows;
    }
}
