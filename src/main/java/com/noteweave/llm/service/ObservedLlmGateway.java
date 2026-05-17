package com.noteweave.llm.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.config.LlmProperties;
import com.noteweave.llm.dto.LlmCallContext;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.dto.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ObservedLlmGateway {

    private final LlmClient llmClient;
    private final LlmCallLogService llmCallLogService;
    private final LlmProperties llmProperties;

    public ObservedLlmResult chat(LlmCallContext context, LlmOptions options) {
        LlmCallContext effectiveContext = LlmCallContext.builder()
                .userId(context.userId())
                .spaceId(context.spaceId())
                .sessionId(context.sessionId())
                .messageId(context.messageId())
                .taskId(context.taskId())
                .artifactId(context.artifactId())
                .scene(context.scene() == null ? null : context.scene().trim().toUpperCase())
                .provider(resolveProvider(context.provider()))
                .model(resolveModel(context.model()))
                .promptVersionId(context.promptVersionId())
                .messages(context.messages())
                .build();
        Long logId = llmCallLogService.begin(effectiveContext);
        Instant start = Instant.now();
        try {
            LlmResponse response = llmClient.chat(effectiveContext.messages(), options);
            llmCallLogService.markSuccess(logId, TokenUsage.of(response.inputTokens(), response.outputTokens()), response.latencyMs());
            return new ObservedLlmResult(logId, response);
        } catch (BusinessException ex) {
            llmCallLogService.markFailure(logId, ex.getErrorCode().name(), ex.getMessage(), elapsed(start));
            throw ex;
        } catch (Exception ex) {
            llmCallLogService.markFailure(logId, ErrorCode.LLM_CALL_FAILED.name(), ex.getMessage(), elapsed(start));
            throw ex;
        }
    }

    private String resolveProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            return provider.trim();
        }
        if (llmProperties.stub() != null && llmProperties.stub().enabled()) {
            return llmProperties.stub().provider();
        }
        return "openai-compatible";
    }

    private String resolveModel(String model) {
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        if (llmProperties.stub() != null && llmProperties.stub().enabled()) {
            return llmProperties.stub().model();
        }
        return llmProperties.api() == null ? "unknown" : llmProperties.api().model();
    }

    private long elapsed(Instant start) {
        return Math.max(1L, Duration.between(start, Instant.now()).toMillis());
    }

    public record ObservedLlmResult(Long logId, LlmResponse response) {
    }
}
