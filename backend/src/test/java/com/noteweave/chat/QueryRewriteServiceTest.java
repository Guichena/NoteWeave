package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.conversation.ConversationContextProjectionService;
import com.noteweave.retrieval.QueryRewrite;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QueryRewriteServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationRetrievalContextAssembler assembler = new ConversationRetrievalContextAssembler();

    @Test
    void firstTurnSkipsTheModel() {
        ChatLlmClient llm = enabledClient();
        QueryRewrite rewrite = service(llm, true).rewrite(assembler.empty("PostgreSQL 的 GIN 索引怎么用"));

        assertThat(rewrite.standalone()).isTrue();
        assertThat(rewrite.standaloneQuery()).isEqualTo("PostgreSQL 的 GIN 索引怎么用");
        assertThat(rewrite.keywordQuery()).isEqualTo(rewrite.standaloneQuery());
        assertThat(rewrite.method()).isEqualTo("RULE");
        assertThat(rewrite.degradationReasons()).isEmpty();
        assertThat(rewrite.channels().legacySingleQuery()).isTrue();
        verify(llm, never()).streamChat(any(), any(), anyInt(), any());
    }

    @Test
    void unavailableModelKeepsTheQuestionAndAppendsTheTopicAnchor() {
        ChatLlmClient llm = mock(ChatLlmClient.class);
        when(llm.isEnabled()).thenReturn(false);

        QueryRewrite rewrite = service(llm, true).rewrite(followUp("它为什么重要"));

        assertThat(rewrite.standalone()).isFalse();
        assertThat(rewrite.method()).isEqualTo("RULE");
        assertThat(rewrite.degradationReasons()).containsExactly("QUERY_REWRITE_LLM_UNAVAILABLE");
        assertThat(rewrite.standaloneQuery()).isEqualTo("它为什么重要");
        assertThat(rewrite.keywordQuery()).isEqualTo("它为什么重要 AnchorAlpha");
        assertThat(rewrite.mustTerms()).contains("anchoralpha");
        verify(llm, never()).streamChat(any(), any(), anyInt(), any());
    }

    @Test
    void disabledSwitchUsesTheSameRuleWithoutCallingTheModel() {
        ChatLlmClient llm = enabledClient();

        QueryRewrite rewrite = service(llm, false).rewrite(followUp("它为什么重要"));

        assertThat(rewrite.method()).isEqualTo("RULE");
        assertThat(rewrite.degradationReasons()).containsExactly("QUERY_REWRITE_DISABLED");
        assertThat(rewrite.keywordQuery()).contains("AnchorAlpha");
        verify(llm, never()).streamChat(any(), any(), anyInt(), any());
    }

    @Test
    void acceptsADependentRewriteAndKeepsAlternativesForLowRecall() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(any(), any(), anyInt(), any())).thenReturn(validRewrite(false));

        QueryRewrite rewrite = service(llm, true).rewrite(followUp("它为什么重要"));

        assertThat(rewrite.method()).isEqualTo("LLM");
        assertThat(rewrite.standalone()).isFalse();
        assertThat(rewrite.standaloneQuery()).isEqualTo("AnchorAlpha 的第二点为什么重要");
        assertThat(rewrite.keywordQuery()).isEqualTo("它为什么重要 AnchorAlpha 第二点");
        assertThat(rewrite.alternatives()).containsExactly("AnchorAlpha 证据窗口");
        assertThat(rewrite.mustTerms()).containsExactly("AnchorAlpha");
        assertThat(rewrite.lowLevelKeywords()).containsExactly("证据窗口");
        assertThat(rewrite.highLevelKeywords()).containsExactly("资料定位");
        assertThat(rewrite.channels().ruleExpansion()).isFalse();
        assertThat(rewrite.channels().vectorQuery()).isEqualTo(rewrite.standaloneQuery());
        assertThat(rewrite.channels().lexicalQuery()).isEqualTo(rewrite.keywordQuery());
        assertThat(rewrite.channels().rerankQuery()).isEqualTo(rewrite.standaloneQuery());
        assertThat(rewrite.degradationReasons()).isEmpty();
    }

    @Test
    void standaloneModelOutputDoesNotDuplicateTheOriginalQuestion() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(any(), any(), anyInt(), any())).thenReturn(validRewrite(true));

        QueryRewrite rewrite = service(llm, true).rewrite(followUp("AnchorAlpha 的第二点为什么重要"));

        assertThat(rewrite.standalone()).isTrue();
        assertThat(rewrite.standaloneQuery()).isEqualTo("AnchorAlpha 的第二点为什么重要");
        assertThat(rewrite.keywordQuery()).isEqualTo(rewrite.standaloneQuery());
        assertThat(rewrite.method()).isEqualTo("LLM");
    }

    @Test
    void invalidOutputIsRetriedOnceWithTheValidationError() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(any(), any(), anyInt(), any())).thenReturn("不是 JSON", validRewrite(false));

        QueryRewrite rewrite = service(llm, true).rewrite(followUp("它为什么重要"));

        assertThat(rewrite.method()).isEqualTo("LLM");
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llm, org.mockito.Mockito.times(2)).streamChat(any(), prompts.capture(), eq(800), any());
        assertThat(prompts.getAllValues().get(1)).contains("结构校验");
    }

    @Test
    void twoInvalidOutputsFallBackToTheRule() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(any(), any(), anyInt(), any())).thenReturn("第一次", "第二次");

        QueryRewrite rewrite = service(llm, true).rewrite(followUp("它为什么重要"));

        assertThat(rewrite.method()).isEqualTo("RULE");
        assertThat(rewrite.degradationReasons()).containsExactly("QUERY_REWRITE_OUTPUT_INVALID");
        assertThat(rewrite.standaloneQuery()).isEqualTo("它为什么重要");
        assertThat(rewrite.keywordQuery()).contains("AnchorAlpha");
        assertThat(rewrite.channels().ruleExpansion()).isTrue();
    }

    @Test
    void modelCallFailureFallsBackToTheRule() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(any(), any(), anyInt(), any())).thenThrow(new IllegalStateException("down"));

        QueryRewrite rewrite = service(llm, true).rewrite(followUp("它为什么重要"));

        assertThat(rewrite.degradationReasons()).containsExactly("QUERY_REWRITE_LLM_CALL_FAILED");
        assertThat(rewrite.method()).isEqualTo("RULE");
        assertThat(rewrite.keywordQuery()).isEqualTo("它为什么重要 AnchorAlpha");
    }

    @Test
    void standaloneFollowUpSkipsTheModelWhenEnabled() {
        ChatLlmClient llm = enabledClient();
        QueryRewriteService skipping = new QueryRewriteService(llm, objectMapper, assembler, true, true);

        QueryRewrite rewrite = skipping.rewrite(followUp("PostgreSQL 的 GIN 索引适合什么查询场景"));

        assertThat(rewrite.standaloneQuery()).isEqualTo("PostgreSQL 的 GIN 索引适合什么查询场景");
        assertThat(rewrite.method()).isEqualTo("RULE_STANDALONE");
        assertThat(rewrite.degradationReasons()).isEmpty();
        verify(llm, never()).streamChat(any(), any(), anyInt(), any());
    }

    @Test
    void contextDependentFollowUpStillCallsTheModel() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(any(), any(), anyInt(), any())).thenReturn(validRewrite(false));
        QueryRewriteService skipping = new QueryRewriteService(llm, objectMapper, assembler, true, true);

        skipping.rewrite(followUp("那它在高并发写入下的表现怎么样"));

        verify(llm).streamChat(any(), any(), anyInt(), any());
    }

    @Test
    void standaloneRuleIsConservative() {
        assertThat(QueryRewriteService.looksStandalone("PostgreSQL 的 GIN 索引适合什么查询场景")).isTrue();
        assertThat(QueryRewriteService.looksStandalone("RabbitMQ 消息持久化需要哪些配置")).isTrue();
        assertThat(QueryRewriteService.looksStandalone("缺点有哪些")).isFalse();
        assertThat(QueryRewriteService.looksStandalone("这个方案在高并发下会有什么问题")).isFalse();
        assertThat(QueryRewriteService.looksStandalone("跟 Kafka 相比各自适合什么场景")).isFalse();
        assertThat(QueryRewriteService.looksStandalone("详细说说落地时要注意哪些事项")).isFalse();
        assertThat(QueryRewriteService.looksStandalone("How does it handle write amplification")).isFalse();
        assertThat(QueryRewriteService.looksStandalone(null)).isFalse();
    }

    private QueryRewriteService service(ChatLlmClient llm, boolean enabled) {
        return new QueryRewriteService(llm, objectMapper, assembler, enabled);
    }

    @Test
    void compactRewriteRetainsEveryRetrievalChannelWithoutRetry() {
        ChatLlmClient llm = enabledClient();
        when(llm.streamChat(any(), any(), anyInt(), any())).thenReturn("""
                {"s":false,"q":"AnchorAlpha 的第二点为什么重要","k":"AnchorAlpha 第二点",
                 "a":["AnchorAlpha 证据窗口"],"t":"reasoning","m":["AnchorAlpha"],
                 "l":["证据窗口"],"h":["资料定位"],"lang":"zh"}
                """);
        QueryRewrite result = service(llm, true).rewrite(followUp("它为什么重要"));
        assertThat(result.method()).isEqualTo("LLM");
        assertThat(result.standaloneQuery()).isEqualTo("AnchorAlpha 的第二点为什么重要");
        assertThat(result.keywordQuery()).isEqualTo("它为什么重要 AnchorAlpha 第二点");
        assertThat(result.alternatives()).containsExactly("AnchorAlpha 证据窗口");
        assertThat(result.mustTerms()).containsExactly("AnchorAlpha");
        assertThat(result.lowLevelKeywords()).containsExactly("证据窗口");
        assertThat(result.highLevelKeywords()).containsExactly("资料定位");
        verify(llm).streamChat(any(), any(), anyInt(), any());
    }

    private ChatLlmClient enabledClient() {
        ChatLlmClient llm = mock(ChatLlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        return llm;
    }

    private ConversationRetrievalContextAssembler.Context followUp(String question) {
        return new ConversationRetrievalContextAssembler.Context(
                question,
                "当前问题：" + question + "\n主题锚点：AnchorAlpha",
                true,
                2,
                "AnchorAlpha",
                "前序在讨论 AnchorAlpha",
                new ConversationContextProjectionService.Projection(List.of(), List.of()),
                null);
    }

    private String validRewrite(boolean standalone) {
        return """
                {
                  "is_standalone": %s,
                  "standalone_query": "AnchorAlpha 的第二点为什么重要",
                  "keyword_query": "AnchorAlpha 第二点",
                  "alternatives": ["AnchorAlpha 证据窗口"],
                  "question_type": "reasoning",
                  "must_terms": ["AnchorAlpha"],
                  "low_level_keywords": ["证据窗口"],
                  "high_level_keywords": ["资料定位"],
                  "language": "zh"
                }
                """.formatted(standalone);
    }
}
