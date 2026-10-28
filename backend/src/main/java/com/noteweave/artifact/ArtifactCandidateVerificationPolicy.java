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

    static final String DELIVERED_WITH_UNRESOLVED_CHECKS = "DELIVERED_WITH_UNRESOLVED_CHECKS";
    private static final Set<String> SECTION_SUPPORT = Set.of("SUPPORTED", "PARTIAL", "UNSUPPORTED", "NOT_RUN");
    private static final Set<String> STATEMENT_LABELS = Set.of(
            "SUPPORTED", "CONTRADICTED", "UNSUPPORTED", "NON_FACTUAL", "UNDETERMINED");
    private static final Set<String> COMPLETED_ASSESSMENTS = Set.of("ASSESSED", "EXACT_COPY", "COMPLETED");
    private static final Set<String> SEMANTIC_FIELDS = Set.of(
            "contract_status", "evidence_status", "repair_rounds", "citation_recall", "citation_precision", "sections");

    static void validatePipeline(Map<String, Object> payload, String expectedKind, Set<String> sourceIds) {
        require(payload != null && payload.get("ir") instanceof Map<?, ?>, "候选产物缺少新流水线内容");
        require(payload.get("verification") instanceof Map<?, ?> verification
                && "PASS".equals(verification.get("status")) && "PASS".equals(verification.get("contract_status"))
                && "PASS".equals(verification.get("evidence_status")), "新流水线产物未通过最终校验");
        Map<?, ?> ir = (Map<?, ?>) payload.get("ir");
        require(Integer.valueOf(1).equals(ir.get("schema_version")) && expectedKind.equals(ir.get("kind")),
                "产物 Schema 版本或形态与任务不一致");
        require(ir.get("title") instanceof String title && !title.isBlank(), "产物缺少标题");
        require(payload.get("content_ir") instanceof Map<?, ?> old && ir.get("title").equals(old.get("title")),
                "产物标题与阅读页投影不一致");
        require(payload.get("evidence") instanceof Map<?, ?> pack && pack.get("sentences") instanceof List<?>,
                "候选产物缺少素材包");
        List<?> sentences = (List<?>) ((Map<?, ?>) payload.get("evidence")).get("sentences");
        require(!sentences.isEmpty(), "素材包为空");
        Map<String, String> originals = new java.util.HashMap<>();
        for (Object raw : sentences) {
            require(raw instanceof Map<?, ?>, "素材句子格式无效");
            Map<?, ?> sentence = (Map<?, ?>) raw;
            require(sentence.get("id") instanceof String id && !id.isBlank()
                    && sentence.get("text") instanceof String text && !text.isBlank()
                    && sentence.get("chunk_id") instanceof String chunk && !chunk.isBlank()
                    && sourceIds.contains(sentence.get("source_id")), "素材句子缺少编号、正文或合法来源");
            require(originals.putIfAbsent((String) sentence.get("id"), (String) sentence.get("text")) == null,
                    "素材句子编号重复");
        }
        String collection = Map.of("DOC", "sections", "QUIZ", "questions", "CARDS", "cards", "TREE", "root", "TABLE", "rows", "DECK", "slides").get(expectedKind);
        require(collection != null, "产物形态无效");
        if (expectedKind.equals("TREE")) {
            require(ir.get("root") instanceof Map<?, ?> root && root.get("children") instanceof List<?> children && !children.isEmpty(),
                    "思维导图缺少节点");
        } else {
            require(ir.get(collection) instanceof List<?> items && !items.isEmpty(), "产物缺少内容单元");
        }
        validateFrames(ir, sentences);
        require(validateAnchors(ir, originals) > 0, "产物缺少带引用的事实内容");
        require(payload.get("pipeline_stats") instanceof Map<?, ?> stats
                && stats.get("stages") instanceof Map<?, ?> stages
                && stages.keySet().equals(Set.of("gather", "plan", "draft", "check", "render"))
                && stats.get("repair_calls") instanceof Number repair && repair.intValue() >= 0 && repair.intValue() <= 1,
                "流水线阶段或修复次数无效");
    }

    private static void validateFrames(Object node, List<?> sentences) {
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("frame_id")) {
                require(map.get("frame_id") instanceof String id && !id.isBlank()
                        && map.get("caption") instanceof Map<?, ?>, "关键帧引用或图注无效");
                Map<?, ?> caption = (Map<?, ?>) map.get("caption");
                require(caption.get("anchors") instanceof List<?>, "关键帧图注缺少引用");
                List<?> anchors = (List<?>) caption.get("anchors");
                require(sentences.stream().anyMatch(raw -> raw instanceof Map<?, ?> sentence
                        && anchors.contains(sentence.get("id")) && sentence.get("frame_ids") instanceof List<?> frames
                        && frames.contains(map.get("frame_id"))), "插图不属于图注引用的素材关键帧");
            }
            for (Object child : map.values()) validateFrames(child, sentences);
        } else if (node instanceof List<?> list) {
            for (Object child : list) validateFrames(child, sentences);
        }
    }

    private static int validateAnchors(Object node, Map<String, String> originals) {
        int facts = 0;
        if (node instanceof Map<?, ?> map) {
            if (map.containsKey("anchors")) {
                require(map.get("text") instanceof String text && !text.isBlank()
                        && map.get("anchors") instanceof List<?> anchors && !anchors.isEmpty()
                        && anchors.stream().allMatch(originals::containsKey), "产物引用了素材包以外的句子");
                List<?> anchors = (List<?>) map.get("anchors");
                require(anchors.size() == Set.copyOf(anchors).size(), "产物引用包含重复编号");
                java.util.regex.Pattern numbers = java.util.regex.Pattern.compile("[+-]?[0-9]+(?:[.,][0-9]+)*(?:%|％)?");
                String original = anchors.stream().map(originals::get).collect(java.util.stream.Collectors.joining(" "));
                Set<String> allowed = numbers.matcher(original).results().map(java.util.regex.MatchResult::group)
                        .collect(java.util.stream.Collectors.toSet());
                require(numbers.matcher((String) map.get("text")).results().allMatch(m -> allowed.contains(m.group())),
                        "产物事实中的数字与原句不一致");
                facts++;
            }
            for (Object child : map.values()) facts += validateAnchors(child, originals);
        } else if (node instanceof List<?> list) {
            for (Object child : list) facts += validateAnchors(child, originals);
        }
        return facts;
    }

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
        // Worker 在修复轮次用完后仍有证据或契约问题时照常交付并标记；这类版本只核对诊断格式，
        // 校验结果随版本展示给用户，含有明确禁止内容的产物在 Worker 侧已经拦下
        boolean deliveredWithIssues = DELIVERED_WITH_UNRESOLVED_CHECKS.equals(payload.get("delivery_status"));
        require(deliveredWithIssues || verification.get("status") instanceof String status
                && Set.of("PASS", "WARN").contains(status), "候选产物未通过最终校验");
        if (!semantic && !requireSemantic) return;
        require(deliveredWithIssues || "PASS".equals(verification.get("contract_status")), "候选产物未通过声明式契约");
        require(deliveredWithIssues || verification.get("evidence_status") instanceof String evidenceStatus
                && Set.of("PASS", "WARN").contains(evidenceStatus), "候选产物未通过证据校验");
        require(deliveredWithIssues || verification.get("failed_checks") == null
                || (verification.get("failed_checks") instanceof List<?> failed && failed.isEmpty()), "候选产物仍有失败检查项");
        requireEvidence = requireEvidence && !deliveredWithIssues;
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
