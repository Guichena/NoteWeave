package com.noteweave.answer.strategy;

/**
 * REPORT 通道的证据单位：已完成研究报告里引用过的一条网页原文摘录（source_evidence 的一行）。
 * 身份是研究运行编号加证据键，访问范围固定为 workspace-research:{研究运行编号}。
 */
public final class ResearchReportEvidence {

    public static final String CHANNEL = "RESEARCH_REPORT";
    public static final String KIND = "RESEARCH_EVIDENCE";
    public static final String ACCESS_SCOPE_PREFIX = "workspace-research:";
    public static final String EVIDENCE_ID_PREFIX = "research-evidence:";
    /** 检索计划里 REPORT 步骤声明的研究运行编号，按字典序用逗号连接。 */
    public static final String FILTER_RESEARCH_RUN_IDS = "research_run_ids";

    private ResearchReportEvidence() {
    }

    public static String accessScope(String researchRunId) {
        return ACCESS_SCOPE_PREFIX + researchRunId;
    }

    public static String evidenceId(String researchRunId, String evidenceKey) {
        return EVIDENCE_ID_PREFIX + researchRunId + ":" + evidenceKey;
    }

    /** 从访问范围取研究运行编号；不是研究证据或格式不对时返回空串。 */
    public static String researchRunId(EvidenceBundle.Evidence evidence) {
        if (evidence == null || !KIND.equals(evidence.kind()) || evidence.accessScope() == null
                || !evidence.accessScope().startsWith(ACCESS_SCOPE_PREFIX)) {
            return "";
        }
        return evidence.accessScope().substring(ACCESS_SCOPE_PREFIX.length());
    }
}
