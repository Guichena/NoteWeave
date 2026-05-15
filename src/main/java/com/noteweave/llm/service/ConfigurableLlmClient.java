package com.noteweave.llm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.config.LlmProperties;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@ConditionalOnProperty(prefix = "noteweave.llm.stub", name = "enabled", havingValue = "false", matchIfMissing = true)
public class ConfigurableLlmClient implements LlmClient {

    private final LlmProperties llmProperties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public ConfigurableLlmClient(LlmProperties llmProperties, ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(llmProperties.api().baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmOptions options) {
        if (llmProperties.api().apiKey() == null || llmProperties.api().apiKey().isBlank()) {
            throw new BusinessException(ErrorCode.LLM_CONFIG_MISSING, "LLM_API_KEY is missing");
        }
        Instant start = Instant.now();
        Map<String, Object> payload = Map.of(
                "model", llmProperties.api().model(),
                "messages", messages,
                "temperature", options == null || options.temperature() == null ? llmProperties.api().temperature() : options.temperature(),
                "max_tokens", options == null || options.maxTokens() == null ? llmProperties.api().maxTokens() : options.maxTokens()
        );
        try {
            String body = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + llmProperties.api().apiKey())
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(Math.max(5, llmProperties.api().timeoutSeconds())));
            JsonNode root = objectMapper.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new BusinessException(ErrorCode.LLM_CALL_FAILED, "Empty LLM response");
            }
            return LlmResponse.builder()
                    .provider("openai-compatible")
                    .model(llmProperties.api().model())
                    .content(content)
                    .inputTokens(root.path("usage").path("prompt_tokens").asInt(Math.max(1, content.length() / 4)))
                    .outputTokens(root.path("usage").path("completion_tokens").asInt(Math.max(1, content.length() / 4)))
                    .latencyMs(Math.max(1L, Duration.between(start, Instant.now()).toMillis()))
                    .build();
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.LLM_CALL_FAILED, "LLM call failed: " + ex.getMessage());
        }
    }
}
