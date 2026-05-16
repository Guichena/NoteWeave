package com.noteweave.llm.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.config.LlmProperties;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "noteweave.llm.stub", name = "enabled", havingValue = "true")
public class StubLlmClient implements LlmClient {

    private static final String FALLBACK = "暂无相关信息。当前资料不足，无法给出可靠结论。";

    private final LlmProperties llmProperties;

    public StubLlmClient(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, LlmOptions options) {
        if (messages == null || messages.isEmpty()) {
            throw new BusinessException(ErrorCode.LLM_CALL_FAILED, "prompt is empty");
        }
        Instant start = Instant.now();
        String prompt = messages.get(messages.size() - 1).content();
        String answer = extractAnswer(prompt);
        long latency = Math.max(1L, Duration.between(start, Instant.now()).toMillis());
        return LlmResponse.builder()
                .provider(llmProperties.stub().provider())
                .model(llmProperties.stub().model())
                .content(answer)
                .inputTokens(Math.max(1, prompt.length() / 4))
                .outputTokens(Math.max(1, answer.length() / 4))
                .latencyMs(latency)
                .build();
    }

    private String extractAnswer(String prompt) {
        int firstEvidence = prompt.indexOf("[来源#1]");
        if (firstEvidence < 0) {
            return FALLBACK;
        }
        int contentStart = prompt.indexOf("内容：", firstEvidence);
        if (contentStart < 0) {
            return FALLBACK;
        }
        String excerpt = prompt.substring(contentStart + "内容：".length()).trim();
        int nextSection = excerpt.indexOf("\n\n");
        if (nextSection > 0) {
            excerpt = excerpt.substring(0, nextSection).trim();
        }
        return "根据已检索到的资料，[来源#1] 提到：" + excerpt;
    }
}
