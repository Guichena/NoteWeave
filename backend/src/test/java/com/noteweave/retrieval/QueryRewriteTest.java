package com.noteweave.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.JsonNode;
import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.chat.NoteAnswerModeStrategy;
import com.noteweave.chat.NoteRetrievalSnapshotCodec;
import com.noteweave.chat.QaAnswerModeStrategy;
import com.noteweave.chat.WikiAnswerModeStrategy;
import com.noteweave.chat.WikiRetrievalSnapshotCodec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QueryRewriteTest {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .findAndRegisterModules();

    @Test
    void viewSerializesIsStandaloneAndRoundTripsFromStoredPlan() throws Exception {
        QueryRewrite rewrite = llmRewrite();
        JsonNode view = objectMapper.readTree(objectMapper.writeValueAsString(rewrite.view()));

        assertThat(view.path("is_standalone").asBoolean()).isFalse();
        assertThat(view.has("standalone")).isFalse();
        assertThat(view.path("standalone_query").asText()).isEqualTo("独立问题");
        assertThat(view.path("keyword_query").asText()).isEqualTo("原问题 关键词");
        assertThat(view.path("method").asText()).isEqualTo("LLM");
        assertThat(view.path("degradation_reasons")).isEmpty();

        Map<String, String> filters = new LinkedHashMap<>();
        QueryRewrite.copyInto(filters, rewrite.attributes("原问题"));
        String plan = objectMapper.writeValueAsString(Map.of(
                "steps", java.util.List.of(Map.of("filters", filters))));
        String escaped = objectMapper.writeValueAsString(plan);

        assertThat(QueryRewrite.viewFromPlanJson(plan).standaloneQuery()).isEqualTo("独立问题");
        assertThat(QueryRewrite.viewFromPlanJson(escaped).keywordQuery()).isEqualTo("原问题 关键词");
        assertThat(QueryRewrite.viewFromPlanJson(escaped).isStandalone()).isFalse();
        assertThat(QueryRewrite.viewFromPlanJson("{\"steps\":[{\"filters\":{}}]}")).isNull();
    }

    @Test
    void plansWithoutRewriteKeepTheOldSingleQuery() {
        RetrievalChannels channels = QueryRewrite.channelsFrom(Map.of("workspace_id", "workspace"), "原查询");

        assertThat(channels.legacySingleQuery()).isTrue();
        assertThat(channels.vectorQuery()).isEqualTo("原查询");
        assertThat(channels.lexicalQuery()).isEqualTo("原查询");
    }

    @Test
    void threeModesCopyRewriteFiltersIntoThePlan() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("request_content", "原问题");
        attributes.put("question_type", "definition");
        attributes.putAll(llmRewrite().attributes("原问题"));
        AnswerContext context = new AnswerContext(
                "workspace", "conversation", "message", "独立问题",
                Set.of(), attributes, Instant.now());

        assertThat(new QaAnswerModeStrategy().plan(context).steps().get(0).filters())
                .containsEntry(QueryRewrite.FILTER_METHOD, "LLM")
                .containsEntry(QueryRewrite.FILTER_STANDALONE_QUERY, "独立问题")
                .containsEntry(QueryRewrite.FILTER_KEYWORD_QUERY, "原问题 关键词");
        assertThat(new NoteAnswerModeStrategy(new NoteRetrievalSnapshotCodec(objectMapper))
                .plan(context).steps().get(0).filters())
                .containsEntry("source_status", "READY")
                .containsEntry(QueryRewrite.FILTER_METHOD, "LLM");
        assertThat(new WikiAnswerModeStrategy(new WikiRetrievalSnapshotCodec(objectMapper))
                .plan(context).steps().get(0).filters())
                .containsEntry("item_status", "ACTIVE")
                .containsEntry(QueryRewrite.FILTER_KEYWORD_QUERY, "原问题 关键词");
        assertThat(QueryRewrite.channelsFrom(
                new QaAnswerModeStrategy().plan(context).steps().get(0).filters(), "回退"))
                .satisfies(channels -> {
                    assertThat(channels.vectorQuery()).isEqualTo("独立问题");
                    assertThat(channels.lexicalQuery()).isEqualTo("原问题 关键词");
                    assertThat(channels.rerankQuery()).isEqualTo("独立问题");
                    assertThat(channels.ruleExpansion()).isFalse();
                    assertThat(channels.mustTerms()).containsExactly("必须");
                    assertThat(channels.titleTerms()).containsExactly("低层");
                    assertThat(channels.bodyTerms()).containsExactly("高层");
                });
    }

    private QueryRewrite llmRewrite() {
        return new QueryRewrite(
                false,
                "独立问题",
                "原问题 关键词",
                java.util.List.of("另一种说法"),
                "reasoning",
                java.util.List.of("必须"),
                java.util.List.of("低层"),
                java.util.List.of("高层"),
                "zh",
                "LLM",
                java.util.List.of());
    }
}
