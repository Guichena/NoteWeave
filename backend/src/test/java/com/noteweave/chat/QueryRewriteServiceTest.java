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

    private QueryRewriteService service(ChatLlmClient llm, boolean enabled) {
        return new QueryRewriteService(llm, objectMapper, assembler, enabled);
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
