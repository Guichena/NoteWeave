package com.noteweave.answer.generation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成契约：把「这次回答允许说什么、允许引用什么」冻结下来。
 *
 * <p>契约在最终证据选择、充分性判定和提示校验之后生成，写进 {@code answer_run.generation_contract_json}。
 * 生成调用读同一份契约里的系统提示与编号绑定，流式规范化、落库和前端角标都按同一份绑定解析，
 * 不再各自按标题或排名猜对应关系。</p>
 *
 * <p>{@code allowedEvidenceIds} 是最终提示实际引用的证据集合；{@code evidenceTextHashes} 记录冻结时的
 * 正文摘要，用于生成前复核证据是否仍然有效。</p>
 */
public record GenerationContract(
        String schemaVersion,
        String mode,
        String promptVersion,
        String systemPrompt,
        boolean refusal,
        List<String> allowedEvidenceIds,
        List<CitationBinding> citationBindings,
        String evidenceBundleId,
        Map<String, String> evidenceTextHashes,
        List<String> allowedPageTitles
) {
    public static final String SCHEMA_VERSION = "generation-contract-v1";

    public GenerationContract {
        schemaVersion = schemaVersion == null ? "" : schemaVersion;
        mode = mode == null ? "" : mode;
        promptVersion = promptVersion == null ? "" : promptVersion;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        allowedEvidenceIds = allowedEvidenceIds == null ? List.of() : List.copyOf(allowedEvidenceIds);
        citationBindings = citationBindings == null ? List.of() : List.copyOf(citationBindings);
        evidenceBundleId = evidenceBundleId == null ? "" : evidenceBundleId;
        evidenceTextHashes = evidenceTextHashes == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(evidenceTextHashes));
        allowedPageTitles = allowedPageTitles == null ? List.of() : List.copyOf(allowedPageTitles);
    }

    /** 新契约损坏时显式失败，不得借空集合或缺失字段进入历史兼容。 */
    public void requireValid() {
        boolean invalid = !SCHEMA_VERSION.equals(schemaVersion)
                || !java.util.Set.of("QA", "NOTE", "WIKI").contains(mode)
                || promptVersion.isBlank() || evidenceBundleId.isBlank() || systemPrompt.isBlank()
                || allowedEvidenceIds.stream().anyMatch(id -> id == null || id.isBlank())
                || new java.util.HashSet<>(allowedEvidenceIds).size() != allowedEvidenceIds.size()
                || (refusal && (!allowedEvidenceIds.isEmpty() || !citationBindings.isEmpty()))
                || citationBindings.size() != allowedEvidenceIds.size();
        java.util.Set<Integer> numbers = new java.util.HashSet<>();
        java.util.Set<String> bound = new java.util.HashSet<>();
        for (CitationBinding binding : citationBindings) {
            invalid |= binding.number() < 1 || binding.number() > 9999
                    || !numbers.add(binding.number()) || !bound.add(binding.evidenceId())
                    || !allowedEvidenceIds.contains(binding.evidenceId())
                    || binding.citationIds().stream().anyMatch(id -> id == null || id.isBlank());
        }
        for (String id : allowedEvidenceIds) {
            String hash = evidenceTextHashes.get(id);
            invalid |= hash == null || hash.isBlank();
        }
        if (invalid) {
            throw new com.noteweave.common.BusinessException("ANSWER_GENERATION_CONTRACT_INVALID",
                    "冻结生成契约缺少必要身份、正文摘要或唯一引用绑定",
                    org.springframework.http.HttpStatus.CONFLICT);
        }
    }

    /** 拒答时不允许引用任何证据。 */
    public boolean allows(String evidenceId) {
        return !refusal && evidenceId != null && allowedEvidenceIds.contains(evidenceId);
    }

    /** 编号是否在绑定范围内。 */
    public boolean hasNumber(int number) {
        return citationBindings.stream().anyMatch(binding -> binding.number() == number);
    }

    /** 编号对应的证据身份；没有绑定返回 null。 */
    public String evidenceIdOf(int number) {
        return citationBindings.stream()
                .filter(binding -> binding.number() == number)
                .map(CitationBinding::evidenceId)
                .findFirst()
                .orElse(null);
    }

    /** 本次允许按页面名引用的知识页标题。模型看到过某个页面名不等于这页已被选中。 */
    public boolean allowsPage(String title) {
        return !refusal && title != null && allowedPageTitles.contains(title);
    }
}
