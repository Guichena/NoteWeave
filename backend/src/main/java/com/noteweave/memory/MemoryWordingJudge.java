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
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String output = llmClient.streamChat(PROMPT, user, 80, token -> { });
                Boolean parsed = parse(objectMapper, output);
                if (parsed != null) {
                    return parsed;
                }
                user = user + "\n上次输出不合规，只输出 JSON。";
            } catch (RuntimeException ex) {
                log.warn("Memory wording judge failed: {}", ex.getMessage());
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
