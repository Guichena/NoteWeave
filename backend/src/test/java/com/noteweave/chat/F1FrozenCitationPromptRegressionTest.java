package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.generation.CitationAssemblyResult;
import com.noteweave.answer.generation.GenerationContract;
import com.noteweave.answer.generation.GenerationContractCompiler;
import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.PromptSpec;
import com.noteweave.answer.strategy.ScoreInfo;
import com.noteweave.knowledge.KnowledgePageHit;
import com.noteweave.knowledge.WikiPageContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 三模式提示只允许冻结编号 [n]，编号顺序与证据顺序、引用绑定一致。 */
class F1FrozenCitationPromptRegressionTest {

    private final WikiRetrievalSnapshotCodec wikiCodec =
            new WikiRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
    private final WikiAnswerModeStrategy wikiStrategy = new WikiAnswerModeStrategy(wikiCodec);
    private final QaAnswerModeStrategy qaStrategy = new QaAnswerModeStrategy();

    @Test
    void wikiPromptNumbersPagesInEvidenceOrderInsteadOfPageNames() {
        WikiRetrievalSnapshot snapshot = new WikiRetrievalSnapshot(
                List.of(page("version-a", "Page A"), page("version-b", "Page B")), List.of());
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", WikiAnswerModeStrategy.PLAN_VERSION,
                List.of(knowledge("knowledge-version:version-b", "version-b", "Page B"),
                        knowledge("knowledge-version:version-a", "version-a", "Page A")),
                false, List.of(), Instant.now(),
                Map.of(WikiRetrievalSnapshotCodec.METADATA_KEY, wikiCodec.encode(snapshot)));
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(), Map.of(), Instant.now());

        PromptSpec prompt = wikiStrategy.compose(context, bundle);

        assertThat(prompt.userPrompt())
                .contains("[1] Page B")
                .contains("[2] Page A")
                .doesNotContain("[[");
        assertThat(prompt.referencedEvidenceIds())
                .containsExactly("knowledge-version:version-b", "knowledge-version:version-a");

        GenerationContract contract = GenerationContractCompiler.compile(
                "WIKI", prompt, bundle, CitationAssemblyResult.empty());
        contract.requireValid();
        assertThat(prompt.userPrompt()).contains("只使用下面的编号 [n]");
        assertThat(contract.systemPrompt()).doesNotContain("[[");
        assertThat(contract.citationBindings())
                .extracting(binding -> binding.number() + ":" + binding.evidenceId())
                .containsExactly("1:knowledge-version:version-b", "2:knowledge-version:version-a");
    }

    @Test
    void qaPromptNumbersPassagesAndBindsThemInTheSameOrder() {
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", "qa-plan-v1",
                List.of(passage("passage:one", "资料一"), passage("passage:two", "资料二")),
                false, List.of(), Instant.now());
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(), Map.of(), Instant.now());

        PromptSpec prompt = qaStrategy.compose(context, bundle);

        assertThat(prompt.userPrompt())
                .contains("[1] 《资料一》")
                .contains("[2] 《资料二》")
                .doesNotContain("[[");
        assertThat(prompt.systemPrompt()).contains("[n]");

        GenerationContract contract = GenerationContractCompiler.compile(
                "QA", prompt, bundle, CitationAssemblyResult.empty());
        contract.requireValid();
        assertThat(contract.citationBindings())
                .extracting(binding -> binding.number() + ":" + binding.evidenceId())
                .containsExactly("1:passage:one", "2:passage:two");
    }

    private WikiPageContext page(String versionId, String title) {
        return new WikiPageContext(
                new KnowledgePageHit("item-" + versionId, versionId, 1, title, "content", "summary", 1),
                List.of(), List.of(), List.of());
    }

    private EvidenceBundle.Evidence knowledge(String id, String versionId, String title) {
        return new EvidenceBundle.Evidence(
                id, "KNOWLEDGE_VERSION", "", "", "", "item-" + versionId, versionId, title,
                "摘录", "location", 0, 0.5, 0.5, "scope", Instant.now(), "wiki-page-graph", 1,
                Map.of(), ScoreInfo.provider("", 0.5));
    }

    private EvidenceBundle.Evidence passage(String id, String title) {
        return new EvidenceBundle.Evidence(
                id, "PASSAGE", "source", "snapshot", "chunk", "", "", title, "摘录", "location",
                0, 0.5, 0.5, "scope", Instant.now(), "selected", 1, Map.of(),
                ScoreInfo.provider("rerank-v1", 0.5));
    }
}
