package com.noteweave.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.OpenAiSdkClients;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.io.IOException;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 基于 OpenAI 官方 Java SDK 的 chat.completions 流式调用实现，兼容任意 OpenAI 协议的服务。
 * <p>
 * SSE 解析、错误映射和超时由 SDK 处理；本类负责组装请求、逐段回调 token，
 * 并要求流以带 {@code finish_reason} 的分片正常结束且有可见内容。
 * 配置缺失时 {@link #isEnabled()} 返回 {@code false}，由 ChatService.stream 走模板 chunk 回退分支。
 */
@Component
public class HttpChatLlmClient implements ChatLlmClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final boolean enabled;
    private final String model;
    private final OpenAIClient client;

    public HttpChatLlmClient(ObjectMapper objectMapper, com.noteweave.config.NoteWeaveProperties properties) {
        com.noteweave.config.NoteWeaveProperties.Llm llm = properties.llm();
        this.enabled = llm != null && llm.enabled()
                && llm.endpoint() != null && !llm.endpoint().isBlank()
                && llm.model() != null && !llm.model().isBlank();
        this.model = enabled ? llm.model() : "";
        Duration timeout = Duration.ofSeconds(llm == null ? 60L : Math.max(10L, llm.timeoutSeconds()));
        this.client = enabled
                ? OpenAiSdkClients.create(llm.endpoint(), CHAT_COMPLETIONS_PATH, llm.apiKey(), timeout, 0)
                : null;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        return streamChat(systemPrompt, userPrompt, 1200, onToken);
    }

    @Override
    public String streamChat(
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens,
            Consumer<String> onToken
    ) {
        if (!enabled) {
            throw new IllegalStateException("HttpChatLlmClient is not enabled; check noteweave.llm.* configuration");
        }
        ChatCompletionCreateParams params = buildRequest(systemPrompt, userPrompt, maximumOutputTokens);
        try (StreamResponse<ChatCompletionChunk> response = client.chat().completions().createStreaming(params)) {
            return consumeStream(response.stream().iterator(), onToken);
        } catch (IOException | OpenAIException ex) {
            throw new IllegalStateException("LLM stream call failed: " + ex.getMessage(), ex);
        }
    }

    ChatCompletionCreateParams buildRequest(
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens
    ) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model)
                .addSystemMessage(systemPrompt)
                .addUserMessage(userPrompt)
                // 兼容服务普遍只认 max_tokens，不用 SDK 推荐的 max_completion_tokens。
                .putAdditionalBodyProperty("max_tokens", JsonValue.from(Math.max(1, maximumOutputTokens)));
        if (model.toLowerCase(Locale.ROOT).startsWith("glm-5")) {
            builder.putAdditionalBodyProperty("thinking", JsonValue.from(Map.of("type", "disabled")));
        }
        return builder.build();
    }

    String consumeStream(Iterator<ChatCompletionChunk> chunks, Consumer<String> onToken) throws IOException {
        StringBuilder accumulated = new StringBuilder();
        boolean finished = false;
        while (chunks.hasNext()) {
            ChatCompletionChunk chunk = chunks.next();
            for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                String token = choice.delta().content().orElse("");
                if (!token.isEmpty()) {
                    accumulated.append(token);
                    onToken.accept(token);
                }
                if (choice.finishReason().isPresent()) {
                    finished = true;
                }
            }
        }
        if (!finished) {
            throw new IOException("LLM stream completed without finish_reason");
        }
        if (accumulated.toString().isBlank()) {
            throw new IOException("LLM stream completed without content");
        }
        return accumulated.toString();
    }
}
