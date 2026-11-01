package com.noteweave.answer.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import com.noteweave.answer.strategy.ScoreInfo;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenerationContractCompilerTest {

    @Test
    void commonRulesComeBeforeModePromptSoModeConstraintsWin() {
        PromptSpec prompt = new PromptSpec("模式提示：只答资料内的事实。", "user", List.of("passage:one"), "qa-v1");

        GenerationContract contract = GenerationContractCompiler.compile(
                "QA", prompt, bundle(passage("passage:one", "资料一")), CitationAssemblyResult.empty());

        assertThat(contract.systemPrompt())
                .startsWith(GenerationContractCompiler.COMMON_RULES)
                .endsWith("模式提示：只答资料内的事实。");
    }

    @Test
    void bindingsFollowPromptNumberingAndCarryCitationIds() {
        PromptSpec prompt = new PromptSpec("system", "user", List.of("passage:two", "passage:one"), "qa-v1");
        CitationAssemblyResult assembly = new CitationAssemblyResult(
                List.of(new CitationBinding(1, "passage:two", List.of("citation-a")),
                        new CitationBinding(2, "passage:one", List.of("citation-b"))), 2);

        GenerationContract contract = GenerationContractCompiler.compile(
                "QA", prompt, bundle(passage("passage:one", "资料一"), passage("passage:two", "资料二")),
                assembly);

        assertThat(contract.citationBindings()).extracting(CitationBinding::number).containsExactly(1, 2);
        assertThat(contract.citationBindings()).extracting(CitationBinding::evidenceId)
                .containsExactly("passage:two", "passage:one");
        assertThat(contract.citationBindings()).extracting(CitationBinding::citationIds)
                .containsExactly(List.of("citation-a"), List.of("citation-b"));
        assertThat(contract.allowedEvidenceIds()).containsExactly("passage:two", "passage:one");
        assertThat(contract.evidenceTextHashes()).containsOnlyKeys("passage:two", "passage:one");
        assertThat(contract.evidenceBundleId()).isEqualTo("bundle");
    }

    @Test
    void refusalContractCarriesNoAllowedEvidence() {
        PromptSpec prompt = new PromptSpec("system", "拒答正文", List.of(), "qa-v1", true);

        GenerationContract contract = GenerationContractCompiler.compile(
                "QA", prompt, bundle(), CitationAssemblyResult.empty());

        assertThat(contract.refusal()).isTrue();
        assertThat(contract.allowedEvidenceIds()).isEmpty();
        assertThat(contract.citationBindings()).isEmpty();
    }

    @Test
    void knowledgePagesBecomeAllowedPageTitles() {
        PromptSpec prompt = new PromptSpec("system", "user",
                List.of("knowledge-version:one", "passage:one"), "wiki-v1");

        GenerationContract contract = GenerationContractCompiler.compile(
                "WIKI", prompt,
                bundle(knowledge("knowledge-version:one", "路由表"), passage("passage:one", "资料一")),
                CitationAssemblyResult.empty());

        assertThat(contract.allowedPageTitles()).containsExactly("路由表");
        assertThat(contract.allowsPage("路由表")).isTrue();
        assertThat(contract.allowsPage("未选页面")).isFalse();
    }

    @Test
    void emptyModePromptFallsBackToCommonRulesOnly() {
        PromptSpec prompt = new PromptSpec("  ", "user", List.of(), "qa-v1");

        GenerationContract contract = GenerationContractCompiler.compile(
                "QA", prompt, bundle(), CitationAssemblyResult.empty());

        assertThat(contract.systemPrompt()).isEqualTo(GenerationContractCompiler.COMMON_RULES);
    }

    private EvidenceBundle bundle(EvidenceBundle.Evidence... evidence) {
        return new EvidenceBundle("bundle", "plan-v1", List.of(evidence), false, List.of(), Instant.now());
    }

    private EvidenceBundle.Evidence passage(String id, String title) {
        return new EvidenceBundle.Evidence(
                id, "PASSAGE", "source", "snapshot", "passage", "", "", title, "摘录", "location",
                0, 0.5, 0.5, "scope", Instant.now(), "selected", 4, Map.of(),
                ScoreInfo.provider("rerank-v1", 0.5));
    }

    private EvidenceBundle.Evidence knowledge(String id, String title) {
        return new EvidenceBundle.Evidence(
                id, "KNOWLEDGE_VERSION", "", "", "", "item", "version", title, "摘录", "location",
                0, 0.5, 0.5, "scope", Instant.now(), "wiki-page-graph", 4, Map.of(),
                ScoreInfo.provider("", 0.5));
    }
}
