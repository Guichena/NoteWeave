package com.noteweave.memory;

import com.noteweave.chat.ChatLlmClient;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 判断负面反馈针对回答的哪个方面，再交给槽位映射。
 * 规则能确定时不调用模型。模型不可用或两次输出都不合规时记为未能判断。
 */
@Component
public class MemoryFeedbackAspectJudge {

    public static final String ASPECT_NOT_RUN = "MEMORY_FEEDBACK_ASPECT_NOT_RUN";

    private static final Logger log = LoggerFactory.getLogger(MemoryFeedbackAspectJudge.class);
    private static final Set<String> CONTENT_REASONS = Set.of(
            "内容不准确", "没有回答问题", "引用不支持结论", "资料里有但没找到");
    private static final Set<String> ASPECTS = Set.of(
            "LENGTH", "LANGUAGE", "FORMAT", "STYLE", "CONTENT", "UNKNOWN");
    private static final Pattern ASPECT_FIELD = Pattern.compile(
            "\"aspect\"\\s*:\\s*\"([A-Za-z]+)\"", Pattern.CASE_INSENSITIVE);
    private static final String PROMPT = """
            判断这条反馈针对回答的哪个方面。
            只输出一个 JSON 对象，字段 aspect 取 LENGTH、LANGUAGE、FORMAT、STYLE、CONTENT、UNKNOWN 之一。
            LENGTH 表示长度，LANGUAGE 表示语言，FORMAT 表示格式或结构，STYLE 表示语气或风格，CONTENT 表示事实、引用或是否答到问题上，UNKNOWN 表示无法判断。
            不要输出其他文字。""";

    private final ChatLlmClient llmClient;

    public MemoryFeedbackAspectJudge(ChatLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public Judgment judge(String text) {
        return judge(text, llmClient);
    }

    static Judgment judge(String text, ChatLlmClient client) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isEmpty()) {
            return new Judgment("UNKNOWN", null);
        }
        if (CONTENT_REASONS.contains(normalized)) {
            return new Judgment("CONTENT", null);
        }
        String ruled = rule(normalized);
        if (ruled != null) {
            return new Judgment(ruled, null);
        }
        return askModel(client, normalized);
    }

    private static String rule(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (containsAny(value, "太长", "太短", "简短", "啰嗦", "长度")) {
            return "LENGTH";
        }
        if (containsAny(value, "英文", "中文", "语言")) {
            return "LANGUAGE";
        }
        if (containsAny(value, "格式", "结构", "表格", "分点", "结论")) {
            return "FORMAT";
        }
        if (containsAny(value, "语气", "风格")) {
            return "STYLE";
        }
        return null;
    }

    private static Judgment askModel(ChatLlmClient client, String text) {
        if (client == null || !client.isEnabled()) {
            return new Judgment("UNKNOWN", ASPECT_NOT_RUN);
        }
        String user = "反馈：\n" + text;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String output = client.streamChat(PROMPT, user, 80, token -> { });
                String aspect = readAspect(output);
                if (aspect != null) {
                    return new Judgment(aspect, null);
                }
                user = user + "\n上次输出不合规，只输出 JSON 对象，aspect 取 LENGTH、LANGUAGE、FORMAT、STYLE、CONTENT、UNKNOWN 之一。";
            } catch (RuntimeException ex) {
                log.warn("反馈方面判断失败: {}", ex.getMessage());
                user = user + "\n上次调用失败，只输出 JSON 对象，aspect 取 LENGTH、LANGUAGE、FORMAT、STYLE、CONTENT、UNKNOWN 之一。";
            }
        }
        return new Judgment("UNKNOWN", ASPECT_NOT_RUN);
    }

    static String readAspect(String output) {
        if (output == null) {
            return null;
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        Matcher matcher = ASPECT_FIELD.matcher(output.substring(start, end + 1));
        if (!matcher.find()) {
            return null;
        }
        String aspect = matcher.group(1).toUpperCase(Locale.ROOT);
        return ASPECTS.contains(aspect) ? aspect : null;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public record Judgment(String aspect, String degradationReason) {
    }
}
