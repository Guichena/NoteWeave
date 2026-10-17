package com.noteweave.research;

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

/** 正文引用了引用核对之外的证据时，报告末尾补上原文与网址。 */
class ResearchSupplementaryCitationTest {

    private static final String LISTED = "evidence:" + "a".repeat(48);
    private static final String EXTRA = "evidence:" + "b".repeat(48);

    @Test
    @SuppressWarnings("unchecked")
    void citedEvidenceOutsideTheAuditGetsItsQuoteAndUrlAppended() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        List<String[]> rows = new java.util.ArrayList<>();
        rows.add(new String[] {"JEP 491 targets JDK 24", "snap-1", "https://openjdk.org/jeps/491"});
        when(jdbc.query(anyString(), any(RowMapper.class), eq("run-1"), eq(EXTRA))).thenReturn((List) rows);
        String markdown = "正文 [" + LISTED + "] 与 [" + EXTRA + "]\n\n- Evidence: `" + LISTED + "`\n";

        String result = ResearchAgentIncrementalFinalizationService.appendSupplementaryCitations(jdbc, "run-1", markdown);

        assertThat(result).contains("## 补充引用", "Evidence: `" + EXTRA + "`", "JEP 491 targets JDK 24",
                "https://openjdk.org/jeps/491");
        assertThat(result.indexOf("Evidence: `" + LISTED + "`")).isEqualTo(result.lastIndexOf("Evidence: `" + LISTED + "`"));
    }

    @Test
    void reportWithoutExtraCitationsIsUnchanged() {
        String markdown = "正文 [" + LISTED + "]\n\n- Evidence: `" + LISTED + "`\n";

        assertThat(ResearchAgentIncrementalFinalizationService.appendSupplementaryCitations(mock(JdbcTemplate.class), "run-1", markdown))
                .isEqualTo(markdown);
    }
}
