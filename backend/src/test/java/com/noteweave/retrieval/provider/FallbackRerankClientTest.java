package com.noteweave.retrieval.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 重排服务不可用时改用模型打分，服务可用时不调用模型。 */
class FallbackRerankClientTest {

    private final OpenAiCompatibleRerankClient provider = mock(OpenAiCompatibleRerankClient.class);
    private final ChatLlmClient llm = mock(ChatLlmClient.class);

    @SuppressWarnings("unchecked")
    private FallbackRerankClient client(boolean fallbackEnabled) {
        ObjectProvider<ChatLlmClient> llmProvider = mock(ObjectProvider.class);
        when(llmProvider.getIfAvailable()).thenReturn(llm);
        when(llm.isEnabled()).thenReturn(true);
        return new FallbackRerankClient(provider, llmProvider, new ObjectMapper(), fallbackEnabled);
    }

    @Test
    void disabledProviderFallsBackToModelScoresInRankOrder() {
        when(provider.isEnabled()).thenReturn(false);
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenReturn("{\"scores\":[2,9,9,0]}");

        RerankClient.RerankResult result = client(true).rerank("重排模型", List.of("a", "b", "c", "d"), 3);

        assertThat(result.model()).isEqualTo(FallbackRerankClient.LLM_MODEL);
        assertThat(result.hits()).extracting(RerankClient.Hit::documentIndex).containsExactly(1, 2, 0);
        assertThat(result.hits()).extracting(RerankClient.Hit::score).containsExactly(0.9, 0.9, 0.2);
        assertThat(result.hits()).extracting(RerankClient.Hit::rank).containsExactly(1, 2, 3);
    }

    @Test
    void providerFailureFallsBackAndHealthyProviderSkipsTheModel() {
        when(provider.isEnabled()).thenReturn(true);
        when(provider.rerank(anyString(), any(), anyInt())).thenThrow(new IllegalStateException("402"));
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenReturn("{\"scores\":[1,7]}");
        assertThat(client(true).rerank("q", List.of("a", "b"), 2).hits())
                .extracting(RerankClient.Hit::documentIndex).containsExactly(1, 0);

        OpenAiCompatibleRerankClient healthy = provider;
        org.mockito.Mockito.reset(healthy, llm);
        when(healthy.isEnabled()).thenReturn(true);
        when(healthy.rerank(anyString(), any(), anyInt()))
                .thenReturn(new RerankClient.RerankResult(List.of(new RerankClient.Hit(0, 0.8, 1)), "jina"));
        assertThat(client(true).rerank("q", List.of("a"), 1).model()).isEqualTo("jina");
        verify(llm, never()).streamChat(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void malformedModelOutputIsAnErrorAndFallbackCanBeSwitchedOff() {
        when(provider.isEnabled()).thenReturn(false);
        when(llm.streamChat(anyString(), anyString(), anyInt(), any())).thenReturn("{\"scores\":[1]}");
        assertThatThrownBy(() -> client(true).rerank("q", List.of("a", "b"), 2))
                .hasMessageContaining("RERANK_LLM_RESPONSE_INVALID");

        assertThat(client(false).isEnabled()).isFalse();
    }
}
