package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** 在版本提交前核对 Worker 的最终校验诊断，历史记录读取不受此门控影响。 */
final class ArtifactCandidateVerificationPolicy {
    private ArtifactCandidateVerificationPolicy() {}

    private static final Set<String> SECTION_SUPPORT = Set.of("SUPPORTED", "PARTIAL", "UNSUPPORTED", "NOT_RUN");
    private static final Set<String> STATEMENT_LABELS = Set.of(
            "SUPPORTED", "CONTRADICTED", "UNSUPPORTED", "NON_FACTUAL", "UNDETERMINED");
    private static final Set<String> COMPLETED_ASSESSMENTS = Set.of("ASSESSED", "EXACT_COPY", "COMPLETED");
    private static final Set<String> SEMANTIC_FIELDS = Set.of(
            "contract_status", "evidence_status", "repair_rounds", "citation_recall", "citation_precision", "sections");

    static void validate(Map<String, Object> payload, boolean requireSemantic, boolean requireEvidence) {
        if (payload == null) return;
        Object raw = payload.get("verification");
        boolean candidate = payload.containsKey("candidate");
        if (!(raw instanceof Map<?, ?> verification)) {
            require(!candidate, "候选产物缺少最终校验结果");
            return;
        }
        boolean semantic = verification.keySet().stream().anyMatch(SEMANTIC_FIELDS::contains);
        if (!candidate && !semantic) return;
        require(verification.get("status") instanceof String status && Set.of("PASS", "WARN").contains(status), "候选产物未通过最终校验");
        if (!semantic && !requireSemantic) return;
        require("PASS".equals(verification.get("contract_status")), "候选产物未通过声明式契约");
        require(verification.get("evidence_status") instanceof String evidenceStatus
                && Set.of("PASS", "WARN").contains(evidenceStatus), "候选产物未通过证据校验");
        require(verification.get("failed_checks") == null
                || (verification.get("failed_checks") instanceof List<?> failed && failed.isEmpty()), "候选产物仍有失败检查项");
        validateMetric(verification.get("citation_recall"));
        validateMetric(verification.get("citation_precision"));
        if (requireSemantic) {
            require(verification.containsKey("citation_recall") && verification.containsKey("citation_precision"), "新校验诊断缺少引用指标");
        }
        if (verification.containsKey("llm_calls")) {
            require(nonnegativeInteger(verification.get("llm_calls")), "证据模型调用计数无效");
        }
        Object rawSections = verification.get("sections");
        require(rawSections instanceof List<?>, "证据校验缺少章节诊断");
        List<?> sections = (List<?>) rawSections;
        require(!sections.isEmpty() && sections.size() <= 80, "证据校验章节数量无效");
        List<String> headings = new ArrayList<>();
        boolean factual = false;
        for (Object rawSection : sections) {
            require(rawSection instanceof Map<?, ?>, "章节证据诊断格式无效");
            Map<?, ?> section = (Map<?, ?>) rawSection;
            require(section.get("heading") instanceof String heading && !heading.isBlank(), "章节证据诊断缺少标题");
            headings.add(String.valueOf(section.get("heading")));
            require(section.get("support") instanceof String support && SECTION_SUPPORT.contains(support), "章节证据支撑状态无效");
            require(!requireEvidence || "SUPPORTED".equals(section.get("support")), "必需证据章节存在未完成或未获支持的校验");
            Object rawStatements = section.get("statements");
            if (rawStatements == null && !requireSemantic) continue;
            require(rawStatements instanceof List<?> statements && !statements.isEmpty(), "章节缺少陈述级校验诊断");
            for (Object rawStatement : (List<?>) rawStatements) {
                require(rawStatement instanceof Map<?, ?>, "陈述证据诊断格式无效");
                Map<?, ?> statement = (Map<?, ?>) rawStatement;
                require(statement.get("label") instanceof String label && STATEMENT_LABELS.contains(label), "陈述证据标签无效");
                boolean nonFactual = "NON_FACTUAL".equals(statement.get("label"));
                factual |= !nonFactual;
                if (requireEvidence) {
                    require(statement.get("assessment_status") instanceof String assessment
                            && COMPLETED_ASSESSMENTS.contains(assessment), "必需陈述的证据判断未完成");
                }
                if (requireEvidence && !nonFactual) {
                    require("SUPPORTED".equals(statement.get("label"))
                            && Boolean.TRUE.equals(statement.get("sufficiently_supported")), "必需事实陈述未通过证据校验");
                }
                if (statement.get("citations") instanceof List<?> citations) {
                    for (Object rawCitation : citations) {
                        require(rawCitation instanceof Map<?, ?>, "陈述引用诊断格式无效");
                        Map<?, ?> citation = (Map<?, ?>) rawCitation;
                        require(!("SUPPORTED".equals(statement.get("label"))
                                && "CONTRADICTED".equals(citation.get("label"))), "支持结论与引用中的明确矛盾不一致");
                    }
                }
            }
        }
        if (requireSemantic && requireEvidence && factual) {
            require(verification.get("citation_recall") != null && verification.get("citation_precision") != null,
                    "已校验事实陈述缺少引用指标");
        }
        if (requireSemantic) validateHeadings(payload.get("sections"), headings);
        validateRepairRounds(verification.get("repair_rounds"), headings, requireSemantic);
    }

    private static void validateHeadings(Object rawContent, List<String> actual) {
        require(rawContent instanceof List<?>, "候选产物缺少正文结构");
        List<String> expected = new ArrayList<>();
        for (Object raw : (List<?>) rawContent) {
            require(raw instanceof Map<?, ?> section && section.get("heading") instanceof String, "候选产物章节格式无效");
            expected.add(String.valueOf(((Map<?, ?>) raw).get("heading")));
        }
        require(expected.stream().sorted().toList().equals(actual.stream().sorted().toList()), "证据诊断未覆盖完整正文章节");
    }

    private static void validateRepairRounds(Object raw, List<String> headings, boolean required) {
        if (raw == null && !required) return;
        require(raw instanceof List<?> rounds && rounds.size() <= 2, "产物修复轮数无效");
        int expected = 1;
        for (Object rawRound : (List<?>) raw) {
            require(rawRound instanceof Map<?, ?>, "产物修复轮次格式无效");
            Map<?, ?> round = (Map<?, ?>) rawRound;
            require(nonnegativeInteger(round.get("round")) && ((Number) round.get("round")).intValue() == expected++, "产物修复轮次必须从第一轮连续记录");
            require(stringList(round.get("failed_checks")), "产物修复失败项格式无效");
            require(stringList(round.get("repaired_sections")) && headings.containsAll((List<?>) round.get("repaired_sections")), "产物修复章节不属于正文");
        }
    }

    private static boolean stringList(Object raw) {
        return raw instanceof List<?> list && list.stream().allMatch(value -> value instanceof String text && !text.isBlank());
    }

    private static boolean nonnegativeInteger(Object value) {
        if (value instanceof BigInteger integer) {
            return integer.signum() >= 0 && integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0;
        }
        return (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                && ((Number) value).longValue() >= 0 && ((Number) value).longValue() <= Integer.MAX_VALUE;
    }

    private static void validateMetric(Object value) {
        require(value == null || (value instanceof Number number && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0 && number.doubleValue() <= 1), "引用指标必须是零到一之间的有限数值或空值");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new BusinessException("ARTIFACT_CONTENT_NOT_VERIFIED", message, HttpStatus.CONFLICT);
    }
}
