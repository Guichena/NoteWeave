package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 研究会话与报告的短标题：去掉请求语，取第一句或分句，过长时截断。 */
class ResearchDisplayLabelsShortTitleTest {

    @Test
    void shortTitleDropsRequestPhrasesAndKeepsFirstClause() {
        assertThat(ResearchDisplayLabels.shortTitle(
                "根据现有项目资料，比较研发、数据整理、培训三项预算、职责和验收风险，用研究表逐项核实。", 24))
                .isEqualTo("比较研发、数据整理、培训三项预算、职责和验收风险");
        assertThat(ResearchDisplayLabels.shortTitle("Northwind 项目的负责人、预算与试点安排是什么？", 40))
                .isEqualTo("Northwind 项目的负责人、预算与试点安排是什么");
        assertThat(ResearchDisplayLabels.shortTitle("  ", 24)).isEqualTo("研究");
    }

    @Test
    void longQuestionWithoutBreaksIsTruncatedWithEllipsis() {
        String title = ResearchDisplayLabels.shortTitle("一".repeat(60), 24);
        assertThat(title).hasSize(24).endsWith("…");
        assertThat(ResearchDisplayLabels.reportTitle("一".repeat(300))).startsWith("研究报告：").hasSizeLessThan(50);
    }
}
