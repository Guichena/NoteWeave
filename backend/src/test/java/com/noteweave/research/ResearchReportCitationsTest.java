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

/** 报告引用改为编号形式，末尾统一列出参考来源。 */
class ResearchReportCitationsTest {

    private static final String A = "evidence:" + "a".repeat(48);
    private static final String B = "evidence:" + "b".repeat(48);

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void inlineEvidenceKeysBecomeNumbersAndAuditSectionsMergeIntoOneReferenceList() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("run-1"), eq(A))).thenReturn((List) rows("JEP 444", "https://openjdk.org/jeps/444", "Virtual threads are final in JDK 21."));
        when(jdbc.query(anyString(), any(RowMapper.class), eq("run-1"), eq(B))).thenReturn((List) rows("JEP 491", "https://openjdk.org/jeps/491", "Release 24"));
        String markdown = "# 标题\n\n虚拟线程在 JDK 21 正式发布 [" + A + "] [" + B + "]。再次引用 [" + A + "]。\n\n"
                + "## 引用核对\n\n- Evidence: `" + A + "`\n  - Snapshot: `research/external/x`\n\n"
                + "## 局限\n\n- 无\n\n## 补充引用\n\n- Evidence: `" + B + "`\n";

        String result = ResearchReportCitations.renumber(jdbc, "run-1", markdown);

        assertThat(result).contains("虚拟线程在 JDK 21 正式发布 [1][2]。再次引用 [1]。", "## 局限",
                "## 参考来源", "1. [JEP 444](https://openjdk.org/jeps/444)  `" + A + "`",
                "   > Virtual threads are final in JDK 21.", "2. [JEP 491](https://openjdk.org/jeps/491)");
        assertThat(result).doesNotContain("## 引用核对", "## 补充引用", "research/external/", "[evidence:");
    }

    @Test
    void templateReportWithoutInlineCitationsOnlyDropsSnapshotLines() {
        String markdown = "## 已验证结论\n\n- Evidence: `" + A + "`\n  - Exact quote: “q”\n  - Snapshot: `research/external/x`\n  - URL: https://e.x\n";

        String result = ResearchReportCitations.renumber(mock(JdbcTemplate.class), "run-1", markdown);

        assertThat(result).contains("Exact quote", "URL: https://e.x").doesNotContain("Snapshot:");
    }

    private static List<String[]> rows(String title, String url, String quote) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {title, url, quote});
        return rows;
    }
}
