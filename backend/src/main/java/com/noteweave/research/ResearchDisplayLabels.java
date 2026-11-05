package com.noteweave.research;

import java.util.Map;

/**
 * 研究矩阵和报告的中文显示名。
 * 规划器写入的行列 label 仍是英文契约值（Worker 会用它检索和匹配需求），这里只负责展示。
 */
public final class ResearchDisplayLabels {
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
        // 报告标题也用作导出文件名，原来直接用 240 字以内的整句问题，文件名和列表都被拖长
        return REPORT_TITLE_PREFIX + shortTitle(question, 40);
    }

    private static final java.util.regex.Pattern LEADING_REQUEST = java.util.regex.Pattern.compile(
            "^(请问|请你|请|帮我|麻烦|根据(现有|已有)?(项目)?资料[，,、]?|结合(现有|已有)?资料[，,、]?)+");

    /**
     * 研究会话与报告的短标题：去掉开头的请求语，取第一句或第一个分句，超过上限时截断并加省略号。
     * 验收发现研究会话标题直接用 40 字以上的完整问句，侧栏、列表和导出文件名都很长。
     */
    public static String shortTitle(String question, int maxLength) {
        String value = question == null ? "" : question.strip().replaceAll("\\s+", " ");
        value = LEADING_REQUEST.matcher(value).replaceFirst("").strip();
        if (value.isEmpty()) {
            return "研究";
        }
        int sentenceEnd = indexOfAny(value, "。？！?!；;");
        if (sentenceEnd > 0) {
            value = value.substring(0, sentenceEnd);
        }
        if (value.length() > maxLength) {
            int clauseEnd = indexOfAny(value, "，,：:");
            value = clauseEnd >= 6 && clauseEnd <= maxLength
                    ? value.substring(0, clauseEnd)
                    : value.substring(0, Math.max(1, maxLength - 1)) + "…";
        }
        return value;
    }

    private static int indexOfAny(String value, String characters) {
        for (int index = 0; index < value.length(); index++) {
            if (characters.indexOf(value.charAt(index)) >= 0) {
                return index;
            }
        }
        return -1;
    }
}
