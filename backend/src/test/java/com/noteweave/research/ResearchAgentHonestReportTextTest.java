package com.noteweave.research;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 报告给读者看的局限说明不出现内部原因码，成文里的局限小节由完整的局限列表代替。 */
class ResearchAgentHonestReportTextTest {

    @Test
    void limitationCodesBecomeReadableTextAndUnknownCodesAreDropped() {
        List<String> lines = ResearchAgentHonestReportService.readableLimitations(Arrays.asList(
                "REQUIRED_CELLS_PARTIALLY_RESOLVED", "UNRESOLVED_CELLS_REMAIN", "EVIDENCE_CONFLICT",
                "CONFLICTED_CELL", "SOME_INTERNAL_CODE", "检索范围只覆盖英文资料", null));

        assertThat(lines).containsExactly(
                "部分必答研究项未能核验，结论只覆盖已核验的部分",
                "部分研究项的不同来源相互矛盾",
                "检索范围只覆盖英文资料");
    }

    @Test
    void synthesisLimitationSectionIsRemovedUpToTheNextHeading() {
        String markdown = "# 标题\n\n正文\n\n## 局限\n\n- 部分条目\n\n## 对比表\n\n| a | b |\n";

        String result = ResearchAgentHonestReportService.withoutSection(markdown, "## 局限", "## Limitations");

        assertThat(result).contains("正文", "## 对比表", "| a | b |").doesNotContain("## 局限", "部分条目");
    }
}
