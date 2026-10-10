package com.noteweave.research;

import java.util.Map;

/**
 * 研究矩阵和报告的中文显示名。
 * 规划器写入的行列 label 仍是英文契约值（Worker 会用它检索和匹配需求），这里只负责展示。
 */
final class ResearchDisplayLabels {
    static final String REPORT_TITLE_PREFIX = "研究报告：";

    private static final Map<String, String> BUILT_IN = Map.of(
            "subject", "研究对象",
            "answer", "结论",
            "key_evidence", "关键证据",
            "limitations", "局限与风险",
            "implications", "影响与启示");

    private ResearchDisplayLabels() {
    }

    /** 内置行列键返回中文名，其余沿用规划器给的 label，缺失时退回键本身。 */
    static String of(String key, String plannedLabel) {
        String builtIn = key == null ? null : BUILT_IN.get(key);
        if (builtIn != null) return builtIn;
        if (plannedLabel != null && !plannedLabel.isBlank()) return plannedLabel.strip();
        return key == null ? "" : key;
    }

    static String reportTitle(String question) {
        String value = question == null ? "" : question.strip();
        return REPORT_TITLE_PREFIX + value.substring(0, Math.min(240, value.length()));
    }
}
