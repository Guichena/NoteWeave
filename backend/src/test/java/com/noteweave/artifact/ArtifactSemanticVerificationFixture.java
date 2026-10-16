package com.noteweave.artifact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 非语义宿主流程测试使用的显式合成 Worker 诊断，不用于生产生成或实际模型评测。 */
public final class ArtifactSemanticVerificationFixture {
    private ArtifactSemanticVerificationFixture() {}

    public static void attach(Map<String, Object> payload) {
        if (!(payload.get("sections") instanceof List<?> sections) || sections.isEmpty()) {
            throw new IllegalArgumentException("合成校验诊断需要实际测试正文章节");
        }
        Map<String, Object> verification = new LinkedHashMap<>();
        if (payload.get("verification") instanceof Map<?, ?> old) {
            old.forEach((key, value) -> verification.put(String.valueOf(key), value));
        }
        verification.putIfAbsent("status", "PASS");
        verification.putIfAbsent("contract_status", "PASS");
        verification.putIfAbsent("evidence_status", "PASS");
        verification.putIfAbsent("repair_rounds", List.of());
        verification.putIfAbsent("citation_recall", null);
        verification.putIfAbsent("citation_precision", null);
        verification.putIfAbsent("llm_calls", 0);
        verification.putIfAbsent("statement_verification_version", "artifact-statement-verification.v1");
        verification.putIfAbsent("verification_mode", "TEST_FIXTURE");
        verification.putIfAbsent("sections", sections.stream().map(raw -> {
            if (!(raw instanceof Map<?, ?> section)) throw new IllegalArgumentException("测试章节结构无效");
            return Map.of("heading", section.get("heading"), "support", "SUPPORTED", "statements", List.of(
                    Map.of("text", section.get("body"), "label", "NON_FACTUAL", "assessment_status", "ASSESSED",
                            "sufficiently_supported", false, "citations", List.of())));
        }).toList());
        payload.put("verification", verification);
    }
}
