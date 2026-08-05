package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QaAnswerModeStrategyTest {

    @Test
    void shouldDeclareExplicitSourceScopeInPlanAndPrompt() {
        QaAnswerModeStrategy strategy = new QaAnswerModeStrategy();
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query",
                Set.of("source-b", "source-a"),
                Map.of(),
                Instant.now());

        var plan = strategy.plan(context);
        var prompt = strategy.compose(context, new EvidenceBundle(
                "bundle", QaAnswerModeStrategy.PLAN_VERSION,
                List.of(evidence("passage:one")), false, List.of(), Instant.now()));

        assertThat(plan.version()).isEqualTo(QaAnswerModeStrategy.V2_PLAN_VERSION);
        assertThat(plan.steps()).singleElement().satisfies(step -> assertThat(step.filters())
                .containsEntry("workspace_id", "workspace")
                .containsEntry("snapshot_status", "ACTIVE")
                .containsEntry("strategy_profile", "qa-weknora-hybrid-v1")
                .containsEntry("relevance_policy", "qa-lexical-sufficiency-v2")
                .containsEntry("selection_policy", "qa-source-diverse-budget-v2")
                .containsEntry("strategy_v2_enabled", "true")
                .containsEntry("query_rewrite_version", "qa-conversation-rewrite-v1")
                .containsEntry("query_expansion_policy", "qa-low-recall-expansion-v1")
                .containsEntry("vector_over_recall", "60")
                .containsEntry("keyword_over_recall", "60")
                .containsEntry("fusion_candidate_ceiling", "100")
                .containsEntry("rerank_top_n", "40")
                .containsEntry("rrf_k", "60")
                .containsEntry("vector_weight", "0.7")
                .containsEntry("keyword_weight", "0.3")
                .containsEntry("source_ids", "source-a,source-b"));
        assertThat(plan.budget().maxEvidence()).isEqualTo(6);
        assertThat(plan.budget().maxEvidenceCharacters()).isEqualTo(8_000);
        assertThat(prompt.userPrompt()).contains("- 检索边界：用户显式选择的 2 份资料");
    }

    @Test
    void shouldPreserveLegacyQaAnswerWhileDeclaringBundleEvidenceIds() {
        QaAnswerModeStrategy strategy = new QaAnswerModeStrategy();
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "compare",
                Set.of(), Map.of(
                        QaAnswerModeStrategy.ATTRIBUTE_QUESTION_TYPE, "comparison",
                        QaAnswerModeStrategy.ATTRIBUTE_CONTEXT_APPLIED, "true",
                        QaAnswerModeStrategy.ATTRIBUTE_WINDOW_TURN_COUNT, "2",
                        QaAnswerModeStrategy.ATTRIBUTE_TOPIC_ANCHOR, "RAG",
                        QaAnswerModeStrategy.ATTRIBUTE_TOPIC_SUMMARY, "前序摘要",
                        QaAnswerModeStrategy.ATTRIBUTE_HAS_CHAT_CONTROLS, "true",
                        QaAnswerModeStrategy.ATTRIBUTE_CHAT_CONTROL_SECTION,
                        "## 表达控制\n- 风格约束：简洁\n\n"
                ), Instant.now());
        EvidenceBundle bundle = new EvidenceBundle(
                "bundle", QaAnswerModeStrategy.PLAN_VERSION,
                List.of(evidence("passage:one"), evidence("passage:two")),
                false, List.of(), Instant.now());

        var prompt = strategy.compose(context, bundle);

        assertThat(prompt.referencedEvidenceIds()).containsExactly("passage:one", "passage:two");
        assertThat(prompt.userPrompt())
                .contains("本轮已结合最近连续对话窗口理解这次追问。")
                .contains("本轮还应用了工作台级 Chat Control Pack。")
                .contains("- 检索边界：当前 workspace 内已解析资料")
                .contains("- 检索策略：Chunk 向量召回 + BM25 关键词召回 + 加权 RRF + 真实 rerank + 来源覆盖")
                .contains("- 证据 1《title》：excerpt（chunk:0，score=1，reason=selected）")
                .contains("## 表达控制\n- 风格约束：简洁")
                .contains("引用信息会通过 `citation.upsert` 事件返回")
                .doesNotContain("[passage:one]", "[passage:two]");
        assertThat(strategy.policy().citationRequired()).isTrue();
    }

    @Test
    void shouldMarkInsufficientEvidencePromptAsExplicitRefusal() {
        QaAnswerModeStrategy strategy = new QaAnswerModeStrategy();
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query",
                Set.of(), Map.of(), Instant.now());

        var prompt = strategy.compose(context, new EvidenceBundle(
                "bundle", QaAnswerModeStrategy.PLAN_VERSION,
                List.of(), false, List.of(), Instant.now()));

        assertThat(prompt.refusal()).isTrue();
        assertThat(prompt.referencedEvidenceIds()).isEmpty();
    }

    @Test
    void shouldKeepV2PlanWhenLegacyWorkspaceAttributeIsPresent() {
        QaAnswerModeStrategy strategy = new QaAnswerModeStrategy();
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "query",
                Set.of(),
                Map.of("retrieval.strategy.v2", "false"),
                Instant.now());

        var plan = strategy.plan(context);
        var prompt = strategy.compose(context, new EvidenceBundle(
                "bundle", plan.version(), List.of(), false, List.of(), Instant.now()));

        assertThat(plan.version()).isEqualTo(QaAnswerModeStrategy.V2_PLAN_VERSION);
        assertThat(plan.steps()).singleElement().satisfies(step -> assertThat(step.filters())
                .containsEntry("strategy_profile", "qa-weknora-hybrid-v1")
                .containsEntry("relevance_policy", "qa-lexical-sufficiency-v2")
                .containsEntry("selection_policy", "qa-source-diverse-budget-v2")
                .containsEntry("strategy_v2_enabled", "true"));
        assertThat(plan.budget().maxEvidence()).isEqualTo(6);
        assertThat(plan.budget().maxEvidenceCharacters()).isEqualTo(8_000);
        assertThat(prompt.promptVersion()).isEqualTo(QaAnswerModeStrategy.V2_PLAN_VERSION);
        assertThat(prompt.refusal()).isTrue();
    }

    @Test
    void shouldIgnoreMissingBlankAndInvalidLegacyWorkspaceAttributes() {
        QaAnswerModeStrategy strategy = new QaAnswerModeStrategy();
        AnswerContext missing = new AnswerContext(
                "workspace", "conversation", "message", "query",
                Set.of(), Map.of(), Instant.now());
        AnswerContext blank = new AnswerContext(
                "workspace", "conversation", "message", "query",
                Set.of(),
                Map.of("retrieval.strategy.v2", "  "),
                Instant.now());
        AnswerContext invalid = new AnswerContext(
                "workspace", "conversation", "message", "query", Set.of(),
                Map.of("retrieval.strategy.v2", "maybe"),
                Instant.now());

        assertThat(strategy.plan(missing).version())
                .isEqualTo(QaAnswerModeStrategy.V2_PLAN_VERSION);
        assertThat(strategy.plan(blank).version())
                .isEqualTo(QaAnswerModeStrategy.V2_PLAN_VERSION);
        assertThat(strategy.plan(invalid).version())
                .isEqualTo(QaAnswerModeStrategy.V2_PLAN_VERSION);
    }

    private EvidenceBundle.Evidence evidence(String id) {
        return new EvidenceBundle.Evidence(
                id, "PASSAGE", "source", "snapshot", id, "", "", "title",
                "excerpt", "chunk:0", 1, 1, 1, "workspace", Instant.now(),
                "selected", 7, Map.of()
        );
    }
}
