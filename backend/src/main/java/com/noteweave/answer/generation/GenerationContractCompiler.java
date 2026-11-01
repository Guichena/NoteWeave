package com.noteweave.answer.generation;

import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装生成契约。
 *
 * <p>公共通用规则在前，模式提示在后，顺序固定：模式约束排在最后，不会被公共规则覆盖。
 * 编号绑定直接取引用落库时算出的顺序，不再按标题或排名重新猜。</p>
 */
public final class GenerationContractCompiler {

    /**
     * 公共通用规则。所有模式都适用，因此排在模式提示之前。
     *
     * <p>前端按正文里的二级标题拆出折叠卡片，所以草稿里的二级标题必须原样保留。</p>
     */
    public static final String COMMON_RULES = """
            你是 NoteWeave 知识工作台的统一聊天助手。用户消息是系统整理的回答草稿，或者回答要求加资料片段，请据此写出自然通顺的中文回答。
            回答必须基于其中的资料证据与检索元信息，不要编造未在证据中出现的来源、链接、引用或数字。
            草稿中每个以 ## 开头的二级标题都要原样保留，名称和顺序不变，只改写标题下面的内容；不要新增草稿里没有的二级标题。
            保留引用标注（例如 [1]、[[页面名]]）和列表结构。
            不要在正文里复述系统内部字段，例如 RESOLVED、UNRESOLVED、出链数、反链数、来源计数、chunk 编号。
            最终结果直接作为聊天正文，不要加前言或解释。
            """.trim();

    private GenerationContractCompiler() {
    }

    public static GenerationContract compile(
            String mode,
            PromptSpec prompt,
            EvidenceBundle bundle,
            CitationAssemblyResult assembly
    ) {
        if (prompt == null || bundle == null) {
            throw new IllegalArgumentException("生成契约需要提示与证据包");
        }
        CitationAssemblyResult safeAssembly = assembly == null ? CitationAssemblyResult.empty() : assembly;
        List<String> allowed = List.copyOf(prompt.referencedEvidenceIds());
        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, EvidenceBundle.Evidence> byId = new LinkedHashMap<>();
        for (EvidenceBundle.Evidence evidence : bundle.evidence()) {
            byId.putIfAbsent(evidence.evidenceId(), evidence);
        }
        for (String evidenceId : allowed) {
            EvidenceBundle.Evidence evidence = byId.get(evidenceId);
            if (evidence != null) {
                hashes.put(evidenceId, sha256(evidence.excerpt()));
            }
        }
        List<CitationBinding> bindings = new ArrayList<>();
        for (int index = 0; index < allowed.size(); index++) {
            String evidenceId = allowed.get(index);
            bindings.add(new CitationBinding(index + 1, evidenceId, safeAssembly.citationIdsOf(evidenceId)));
        }
        List<String> pageTitles = new ArrayList<>();
        for (String evidenceId : allowed) {
            EvidenceBundle.Evidence evidence = byId.get(evidenceId);
            if (evidence != null && "KNOWLEDGE_VERSION".equals(evidence.kind())
                    && evidence.title() != null && !evidence.title().isBlank()
                    && !pageTitles.contains(evidence.title())) {
                pageTitles.add(evidence.title());
            }
        }
        return new GenerationContract(
                GenerationContract.SCHEMA_VERSION,
                mode,
                prompt.promptVersion(),
                systemPrompt(prompt),
                prompt.refusal(),
                allowed,
                bindings,
                bundle.bundleId(),
                hashes,
                pageTitles
        );
    }

    /** 公共规则在前，模式提示在后。模式提示为空时只用公共规则。 */
    static String systemPrompt(PromptSpec prompt) {
        String modePrompt = prompt.systemPrompt() == null ? "" : prompt.systemPrompt().trim();
        if (modePrompt.isEmpty()) {
            return COMMON_RULES;
        }
        return COMMON_RULES + "\n\n" + modePrompt;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
