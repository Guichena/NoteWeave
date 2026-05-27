package com.noteweave.studio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.studio.config.StudioMcpProperties;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@ConditionalOnProperty(prefix = "noteweave.studio.mcp.bilibili", name = "remote-enabled", havingValue = "true", matchIfMissing = true)
public class RemoteBilibiliMcpToolService implements StudioMcpTool {

    private final StudioMcpProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public RemoteBilibiliMcpToolService(StudioMcpProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(properties.bilibili().remoteBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String toolName() {
        return "bilibili";
    }

    @Override
    public String displayName() {
        return "Bilibili MCP";
    }

    @Override
    public String description() {
        return "Call the remote Bilibili MCP service and use its structured context output.";
    }

    @Override
    public ToolResult invoke(Map<String, Object> args) {
        Object rawUrl = args == null ? null : args.get("url");
        if (rawUrl == null || rawUrl.toString().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Bilibili MCP tool requires args.url");
        }
        try {
            String body = webClient.post()
                    .uri("/api/v1/mcp/bilibili/invoke")
                    .bodyValue(Map.of("url", rawUrl.toString().trim()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(Math.max(3, properties.bilibili().timeoutSeconds())));
            JsonNode root = objectMapper.readTree(body);
            if (!root.path("success").asBoolean(false)) {
                throw new BusinessException(
                        ErrorCode.PLAN_EXECUTION_FAILED,
                        root.path("message").asText("Remote Bilibili MCP tool call failed")
                );
            }
            JsonNode data = root.path("data");
            String title = data.path("title").asText(null);
            String promptContext = data.path("promptContext").asText(null);
            if (promptContext == null || promptContext.isBlank()) {
                throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "Remote Bilibili MCP tool returned empty prompt context");
            }
            Map<String, Object> output = objectMapper.convertValue(data.path("output"), objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            return new ToolResult(
                    toolName(),
                    displayName(),
                    title,
                    promptContext,
                    output == null ? Map.of() : output
            );
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.PLAN_EXECUTION_FAILED, "Remote Bilibili MCP tool call failed: " + ex.getMessage());
        }
    }
}
