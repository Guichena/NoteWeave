package com.noteweave.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 JDK {@link HttpClient} 的 OpenAI 兼容 chat.completions 流式调用实现。
 * <p>
 * 协议约定：
 * <ul>
 *   <li>请求走标准 OpenAI Chat Completion 接口，启用 {@code stream: true}</li>
 *   <li>响应为 {@code text/event-stream}，按行解析 {@code data: {...}} JSON</li>
 *   <li>流结束以 {@code data: [DONE]} 标记</li>
 * </ul>
 * 配置缺失或响应失败时 {@link #isEnabled()} 返回 {@code false}，由 ChatService.stream
 * 走模板 chunk 回退分支。
 */
@Component
public class HttpChatLlmClient implements ChatLlmClient {

    private static final Logger log = LoggerFactory.getLogger(HttpChatLlmClient.class);

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient httpClient;

    public HttpChatLlmClient(ObjectMapper objectMapper, com.noteweave.config.NoteWeaveProperties properties) {
        this.objectMapper = objectMapper;
        com.noteweave.config.NoteWeaveProperties.Llm llm = properties.llm();
        this.enabled = llm != null && llm.enabled()
                && llm.endpoint() != null && !llm.endpoint().isBlank()
                && llm.model() != null && !llm.model().isBlank();
        this.endpoint = enabled ? llm.endpoint() : "";
        this.model = enabled ? llm.model() : "";
        this.apiKey = llm == null ? "" : (llm.apiKey() == null ? "" : llm.apiKey());
        this.timeout = Duration.ofSeconds(llm == null ? 60L : Math.max(10L, llm.timeoutSeconds()));
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
        Map<String, Object> requestBody = buildRequestBody(
                systemPrompt, userPrompt, maximumOutputTokens);
        try {
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            if (!apiKey.isBlank()) {
                request = HttpRequest.newBuilder(request, (n, v) -> true)
                        .header("Authorization", "Bearer " + apiKey)
                        .build();
            }
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IOException("LLM endpoint returned " + response.statusCode() + ": " + err);
            }
            return consumeStream(response.body(), onToken);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("LLM stream call failed: " + ex.getMessage(), ex);
        }
    }

    Map<String, Object> buildRequestBody(
            String systemPrompt,
            String userPrompt,
            int maximumOutputTokens
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", true);
        body.put("max_tokens", Math.max(1, maximumOutputTokens));
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        return body;
    }

    private String consumeStream(InputStream stream, Consumer<String> onToken) throws IOException {
        StringBuilder accumulated = new StringBuilder();
        AtomicReference<String> lineRef = new AtomicReference<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    break;
                }
                String token = extractDelta(payload);
                if (token != null && !token.isEmpty()) {
                    accumulated.append(token);
                    onToken.accept(token);
                }
                lineRef.set(payload);
            }
        } catch (IOException ex) {
            log.warn("LLM stream read interrupted: {}", ex.getMessage());
            throw ex;
        }
        return accumulated.toString();
    }

    private String extractDelta(String payload) {
        try {
            Map<String, Object> json = objectMapper.readValue(payload, new TypeReference<>() {});
            Object choices = json.get("choices");
            if (!(choices instanceof List<?> list) || list.isEmpty()) {
                return null;
            }
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?> choice)) {
                return null;
            }
            Object delta = choice.get("delta");
            if (delta instanceof Map<?, ?> deltaMap) {
                Object content = deltaMap.get("content");
                if (content instanceof String s) {
                    return s;
                }
            }
            Object message = choice.get("message");
            if (message instanceof Map<?, ?> msgMap) {
                Object content = msgMap.get("content");
                if (content instanceof String s) {
                    return s;
                }
            }
        } catch (Exception ex) {
            log.debug("LLM SSE line not parseable as delta: {}", ex.getMessage());
        }
        return null;
    }
}
