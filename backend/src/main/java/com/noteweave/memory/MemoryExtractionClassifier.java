package com.noteweave.memory;

import com.noteweave.chat.ChatLlmClient;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 抽取前的短分类：这条消息是否包含对回答方式的要求或反馈。 */
final class MemoryExtractionClassifier {
    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionClassifier.class);
    static final String PROMPT = """
            判断这条用户消息是否包含对回答方式的要求或反馈。
            要求包括语言、语气、长度、结构、格式、术语和需要避免的写法。
            反馈包括觉得上一条回答太长、看不懂、不该用英文。
            只回答 yes 或 no。""";

    private MemoryExtractionClassifier() {
    }

    static String decide(ChatLlmClient client, String message) {
        if (client == null || !client.isEnabled()) {
            return "UNAVAILABLE";
        }
        String user = "用户消息：\n" + message;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String output = client.streamChat(PROMPT, user, 10, token -> { });
                String label = label(output);
                if (label != null) {
                    return label;
                }
                user = user + "\n上次输出不合规，只回答 yes 或 no。";
            } catch (RuntimeException ex) {
                log.warn("Memory extraction classifier failed: {}", ex.getMessage());
                user = user + "\n上次调用失败，只回答 yes 或 no。";
            }
        }
        return "UNAVAILABLE";
    }

    static String label(String output) {
        if (output == null) {
            return null;
        }
        String text = output.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z\\u4e00-\\u9fff]", "");
        if ("yes".equals(text) || "是".equals(text)) {
            return "YES";
        }
        if ("no".equals(text) || "否".equals(text)) {
            return "NO";
        }
        return null;
    }
}
