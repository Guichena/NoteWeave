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

    @Test
    void rejectedCandidatesAreGradedBySourceStrength() {
        var weak = new ResearchCellLevels.EvidenceRow("e1", "WEAK_SUPPORT", 800_000, "q", "t", "u");
        var background = new ResearchCellLevels.EvidenceRow("e2", "WEAK_SUPPORT", 0, "q", "t", "u");
        var supports = new ResearchCellLevels.EvidenceRow("e3", "SUPPORTS", 900_000, "q", "t", "u");

        assertThat(ResearchCellLevels.level("EVIDENCE_QUALIFICATION_REJECTED", List.of(supports)))
                .isEqualTo(ResearchCellLevels.SOURCED);
        assertThat(ResearchCellLevels.level("NOT_ENOUGH_INFO", List.of(weak, background)))
                .isEqualTo(ResearchCellLevels.SOURCED);
        assertThat(ResearchCellLevels.level("NOT_ENOUGH_INFO", List.of(background)))
                .isEqualTo(ResearchCellLevels.RELATED);
        // 支持关系的候选被拒是结论与证据对不上，不当作线索
        assertThat(ResearchCellLevels.level("NOT_ENOUGH_INFO", List.of(supports))).isNull();
        assertThat(ResearchCellLevels.levelOf("VERIFIED", null)).isEqualTo(ResearchCellLevels.VERIFIED);
        assertThat(ResearchCellLevels.levelOf("GAP", null)).isEqualTo(ResearchCellLevels.NOT_FOUND);
    }

    @Test
    void limitationLineSaysWhatKindOfSourceWasFound() {
        assertThat(ResearchAgentHonestReportService.leadText(ResearchCellLevels.SOURCED, "NO_SUPPORTED_CANDIDATE"))
                .contains("第三方资料", "待核实");
        assertThat(ResearchAgentHonestReportService.leadText(ResearchCellLevels.RELATED, "NO_SUPPORTED_CANDIDATE"))
                .contains("相关背景材料");
        assertThat(ResearchAgentHonestReportService.leadText(null, "NO_SUPPORTED_CANDIDATE"))
                .isEqualTo(ResearchAgentHonestReportService.reasonText("NO_SUPPORTED_CANDIDATE"));
    }

    @Test
    void verificationSummaryGoesRightAfterTheLeadParagraph() {
        String result = ResearchAgentHonestReportService.afterLead(
                "# 标题\n\n摘要。\n\n## 直接回答\n\n正文", "**核验情况：** 已核验 3 项。");

        assertThat(result).isEqualTo("# 标题\n\n摘要。\n\n**核验情况：** 已核验 3 项。\n\n## 直接回答\n\n正文");
        assertThat(ResearchAgentHonestReportService.afterLead("# 标题\n\n摘要。", "x"))
                .isEqualTo("# 标题\n\n摘要。\n\nx");
    }
}
