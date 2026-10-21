package com.noteweave.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 判断新措辞是否比已有记忆更具体。模型不可用或输出不合规时不改措辞。 */
@Component
public class MemoryWordingJudge {
    private static final Logger log = LoggerFactory.getLogger(MemoryWordingJudge.class);
    private static final String PROMPT = """
            判断新的说法是否比已有记忆更具体，并且仍然是同一条回答偏好。
            只输出 JSON：{"more_specific": true} 或 {"more_specific": false}。不要解释。""";
    /**
     * 单次调用的输出上限。
     * <p>
     * 结论只是一个短 JSON，但推理模型会先把预算花在 {@code reasoning_content} 上：
     * 上限太小（例如 80）时推理阶段就耗尽预算，流正常结束（带 finish_reason）却没有任何 content，
     * 客户端于是抛「LLM stream completed without content」，判断永远失败。
     * 这里与 {@code MemoryExtractionClassifier} 取同一量级。
     */
    static final int MAX_OUTPUT_TOKENS = 600;
    /** 与记忆抽取一致：调用失败或输出不合规只重试一次，仍失败就不改措辞。 */
    private static final int MAX_ATTEMPTS = 2;

    private final ChatLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public MemoryWordingJudge(ChatLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public boolean moreSpecific(String current, String incoming) {
        if (current == null || incoming == null || current.trim().equals(incoming.trim())) {
            return false;
        }
        if (llmClient == null || !llmClient.isEnabled()) {
            return false;
        }
        String user = "已有记忆：\n" + current + "\n新的说法：\n" + incoming;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                String output = llmClient.streamChat(PROMPT, user, MAX_OUTPUT_TOKENS, token -> { });
                Boolean parsed = parse(objectMapper, output);
                if (parsed != null) {
                    return parsed;
                }
                if (output == null || output.isBlank()) {
                    // 推理模型把预算全花在 reasoning_content 上时 content 为空，按调用失败重试一次
                    log.warn("Memory wording judge returned empty content (attempt {}/{})",
                            attempt + 1, MAX_ATTEMPTS);
                }
                user = user + "\n上次输出不合规，只输出 JSON。";
            } catch (RuntimeException ex) {
                // 只打异常类型与原因码：异常消息里可能带用户正文或响应体。
                log.warn("Memory wording judge failed: reason={}, type={}, attempt={}",
                        "WORDING_JUDGE_CALL_FAILED", ex.getClass().getSimpleName(), attempt + 1);
                user = user + "\n上次调用失败，只输出 JSON。";
            }
        }
        return false;
    }

    static Boolean parse(ObjectMapper objectMapper, String output) {
        if (output == null) {
            return null;
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end < start) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(output.substring(start, end + 1));
            JsonNode value = node.get("more_specific");
            return value != null && value.isBoolean() ? value.booleanValue() : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
