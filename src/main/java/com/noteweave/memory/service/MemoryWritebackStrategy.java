package com.noteweave.memory.service;

import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatSession;
import com.noteweave.chat.model.ChatSessionKind;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MemoryWritebackStrategy {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(password|passwd|token|secret|api[_-]?key)");

    public MemoryWriteDecision decide(ChatSession session, ChatMessage userMessage, ChatMessage assistantMessage) {
        if (session.getSessionKind() == ChatSessionKind.DRAFT) {
            return skip("draft session");
        }
        String userContent = normalize(userMessage == null ? null : userMessage.getContent());
        String assistantContent = normalize(assistantMessage == null ? null : assistantMessage.getContent());
        if (userContent.isBlank() || assistantContent.isBlank()) {
            return skip("empty round");
        }
        if (looksSensitive(userContent)) {
            return skip("sensitive content");
        }
        if (userContent.length() < 12 && isGreeting(userContent)) {
            return skip("short greeting");
        }
        boolean preference = containsPreferenceSignal(userContent);
        String topic = preference ? "answer_style" : deriveTopic(userContent);
        BigDecimal confidence = preference ? BigDecimal.valueOf(0.9d) : BigDecimal.valueOf(0.72d);
        BigDecimal importance = preference ? BigDecimal.valueOf(0.88d) : BigDecimal.valueOf(0.7d);
        return MemoryWriteDecision.builder()
                .writeSessionSummary(true)
                .writeSpaceMemory(true)
                .writeUserMemory(preference)
                .topic(topic)
                .userPreferenceSummary(preference ? trimSentence(userContent) : null)
                .spaceSummary(trimSentence(userContent + " " + assistantContent))
                .importanceScore(importance)
                .confidenceScore(confidence)
                .reason(preference ? "stable preference" : "meaningful round")
                .build();
    }

    private MemoryWriteDecision skip(String reason) {
        return MemoryWriteDecision.builder()
                .writeSessionSummary(false)
                .writeSpaceMemory(false)
                .writeUserMemory(false)
                .importanceScore(BigDecimal.ZERO)
                .confidenceScore(BigDecimal.ZERO)
                .reason(reason)
                .build();
    }

    private boolean containsPreferenceSignal(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        return normalized.contains("prefer")
                || normalized.contains("please remember")
                || normalized.contains("remember that i")
                || normalized.contains("我偏好")
                || normalized.contains("请记住")
                || normalized.contains("我喜欢");
    }

    private boolean isGreeting(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        return normalized.equals("hi")
                || normalized.equals("hello")
                || normalized.equals("thanks")
                || normalized.equals("你好")
                || normalized.equals("谢谢");
    }

    private boolean looksSensitive(String content) {
        return EMAIL_PATTERN.matcher(content).find() || SECRET_PATTERN.matcher(content).find();
    }

    private String deriveTopic(String content) {
        String trimmed = trimSentence(content);
        return trimmed.length() <= 48 ? trimmed : trimmed.substring(0, 48);
    }

    private String normalize(String content) {
        return content == null ? "" : content.trim();
    }

    private String trimSentence(String content) {
        String normalized = normalize(content).replaceAll("\\s+", " ");
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
    }
}
