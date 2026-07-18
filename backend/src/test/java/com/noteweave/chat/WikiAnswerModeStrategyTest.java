package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.knowledge.KnowledgeCitationResponse;
import com.noteweave.knowledge.KnowledgePageHit;
import com.noteweave.knowledge.WikiPageContext;
import com.noteweave.knowledge.WikiLinkResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WikiAnswerModeStrategyTest {

    private final WikiRetrievalSnapshotCodec codec =
            new WikiRetrievalSnapshotCodec(new ObjectMapper().findAndRegisterModules());
    private final WikiAnswerModeStrategy strategy = new WikiAnswerModeStrategy(codec);

    @Test
    void shouldReplayLegacyWikiTemplateFromKnowledgeVersionBundle() {
        WikiRetrievalSnapshot snapshot = new WikiRetrievalSnapshot(
                List.of(pageContext()), List.of("citation"));
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", WikiAnswerModeStrategy.PLAN_VERSION, List.of(evidence()), false,
                List.of(), Instant.now(),
                Map.of(WikiRetrievalSnapshotCodec.METADATA_KEY, codec.encode(snapshot))
        );
        AnswerContext answerContext = new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(),
                Map.of(
                        WikiAnswerModeStrategy.ATTRIBUTE_CONTEXT_APPLIED, "true",
                        WikiAnswerModeStrategy.ATTRIBUTE_WINDOW_TURN_COUNT, "2",
                        WikiAnswerModeStrategy.ATTRIBUTE_TOPIC_ANCHOR, "WikiTopic",
                        WikiAnswerModeStrategy.ATTRIBUTE_TOPIC_SUMMARY, "前序摘要",
                        WikiAnswerModeStrategy.ATTRIBUTE_HAS_CHAT_CONTROLS, "true",
                        WikiAnswerModeStrategy.ATTRIBUTE_CHAT_CONTROL_SECTION,
                        "## 表达控制\n- 风格约束：简洁\n\n"
                ), Instant.now());

        var prompt = strategy.compose(answerContext, bundle);

        assertThat(prompt.referencedEvidenceIds()).containsExactly("knowledge-version:version");
        assertThat(prompt.userPrompt())
                .contains("## 相关 Wiki 页面")
                .contains("《Wiki Page》v2")
                .contains("## 关键页面关系")
                .contains("Related：RESOLVED，1 次提及")
                .contains("## 反向引用关系")
                .contains("## 来源回链")
                .contains("Source：quote（chunk:0）")
                .contains("## 页面关系")
                .contains("## 会话上下文")
                .contains("## 表达控制")
                .contains("/workspaces/workspace/wiki");
        assertThat(strategy.plan(answerContext).budget().maxGraphHops()).isEqualTo(1);
        assertThat(strategy.plan(answerContext).budget().maxGraphEdges()).isEqualTo(60);
        assertThat(strategy.plan(answerContext).budget().maxGraphCharacters()).isEqualTo(4_000);
    }

    @Test
    void shouldNotFallbackToOrdinaryRagWithoutWikiPages() {
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", WikiAnswerModeStrategy.PLAN_VERSION, List.of(), false,
                List.of(), Instant.now(), Map.of());
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(), Map.of(), Instant.now());

        var prompt = strategy.compose(context, bundle);

        assertThat(prompt.referencedEvidenceIds()).isEmpty();
        assertThat(prompt.refusal()).isTrue();
        assertThat(prompt.userPrompt())
                .contains("当前 Wiki 知识网络还没有可直接命中的正式页面")
                .contains("本次不退回普通资料 RAG 直接作答");
    }

    @Test
    void shouldRenderOnlyKnowledgeVersionsThatRemainInEvidenceBundle() {
        WikiPageContext selected = pageContext();
        WikiPageContext excluded = new WikiPageContext(
                new KnowledgePageHit(
                        "excluded-item", "excluded-version", 1,
                        "Excluded Wiki Page", "excluded content", "excluded summary", 7),
                List.of(), List.of(), List.of());
        WikiRetrievalSnapshot snapshot = new WikiRetrievalSnapshot(
                List.of(selected, excluded), List.of("citation"));
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", WikiAnswerModeStrategy.PLAN_VERSION, List.of(evidence()), false,
                List.of(), Instant.now(),
                Map.of(WikiRetrievalSnapshotCodec.METADATA_KEY, codec.encode(snapshot))
        );
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(), Map.of(), Instant.now());

        var prompt = strategy.compose(context, bundle);

        assertThat(prompt.userPrompt())
                .contains("Wiki Page")
                .doesNotContain("Excluded Wiki Page")
                .doesNotContain("excluded summary");
        assertThat(prompt.referencedEvidenceIds()).containsExactly("knowledge-version:version");
    }

    private WikiPageContext pageContext() {
        KnowledgePageHit page = new KnowledgePageHit(
                "item", "version", 2, "Wiki Page", "content", "summary", 8);
        WikiLinkResponse link = new WikiLinkResponse(
                "item", "related", "Related", "WIKI_LINK", "RESOLVED", 1);
        KnowledgeCitationResponse citation = new KnowledgeCitationResponse(
                "citation", "source", "Source", "quote", null, "chunk:0", "", "");
        return new WikiPageContext(page, List.of(link), List.of(), List.of(citation));
    }

    private EvidenceBundle.Evidence evidence() {
        return new EvidenceBundle.Evidence(
                "knowledge-version:version", "KNOWLEDGE_VERSION", "", "", "",
                "item", "version", "Wiki Page", "summary", "knowledge-version:version",
                8, 1, 1, "workspace-knowledge:item", null, "wiki-page-graph", 7, Map.of()
        );
    }
}
