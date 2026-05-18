package com.noteweave.llm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.config.LlmProperties;
import com.noteweave.llm.dto.LlmMessage;
import com.noteweave.llm.dto.LlmOptions;
import com.noteweave.llm.dto.LlmResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "noteweave.llm.stub", name = "enabled", havingValue = "true")
public class StubLlmClient implements LlmClient {

    private static final String FALLBACK = "暂无相关信息。当前资料不足，无法给出可靠结论。";

    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    public StubLlmClient(LlmProperties llmProperties, ObjectMapper objectMapper) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
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
        if (prompt.contains("你是 NoteWeave 的个人 Wiki 编译器")) {
            return articleCardJson(prompt);
        }
        if (prompt.contains("你是 NoteWeave 的概念抽取器")) {
            return conceptCardJson(prompt);
        }
        int firstEvidence = prompt.indexOf("[SOURCE#1]");
        if (firstEvidence < 0) {
            return FALLBACK;
        }
        int contentStart = prompt.indexOf("Content: ", firstEvidence);
        if (contentStart < 0) {
            return FALLBACK;
        }
        String excerpt = prompt.substring(contentStart + "Content: ".length()).trim();
        int nextSection = excerpt.indexOf("\n\n");
        if (nextSection > 0) {
            excerpt = excerpt.substring(0, nextSection).trim();
        }
        return "Based on the retrieved evidence, [SOURCE#1] states: " + excerpt;
    }

    private String articleCardJson(String prompt) {
        String title = extractField(prompt, "title=");
        Long sourceId = extractLongField(prompt, "sourceId=");
        String text = extractSourceText(prompt);
        String quote = firstSentence(text);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", defaultIfBlank(title, "Stub Article Card"));
        payload.put("summary", summarize(text));
        payload.put("keyPoints", List.of(
                clip(quote, 120),
                clip(secondSentence(text), 120)
        ));
        payload.put("tags", List.of("stub", "integration", "research"));
        payload.put("evidenceQuotes", List.of(Map.of(
                "quote", clip(defaultIfBlank(quote, text), 240),
                "sourceId", sourceId == null ? 1L : sourceId,
                "reason", "Stub compiler extracted the first grounded sentence."
        )));
        return toJson(payload);
    }

    private String conceptCardJson(String prompt) {
        Long sourceId = extractLongField(prompt, "sourceId=");
        String text = extractSourceText(prompt);
        String quote = clip(defaultIfBlank(firstSentence(text), text), 240);
        String primaryConcept = detectPrimaryConcept(text);
        String secondaryConcept = text.toLowerCase().contains("citation") ? "Citation Grounding" : "Context Recovery";

        Map<String, Object> firstConcept = new LinkedHashMap<>();
        firstConcept.put("name", primaryConcept);
        firstConcept.put("aliases", List.of("stub-" + slug(primaryConcept)));
        firstConcept.put("definition", primaryConcept + " is a core topic extracted from the source text.");
        firstConcept.put("explanation", summarize(text));
        firstConcept.put("useCases", List.of("Integration verification", "Runtime reasoning"));
        firstConcept.put("commonMisunderstandings", List.of("Treating this stub output as production quality content."));
        firstConcept.put("evidence", Map.of(
                "sourceId", sourceId == null ? 1L : sourceId,
                "quote", quote
        ));
        firstConcept.put("confidence", 0.82d);

        Map<String, Object> secondConcept = new LinkedHashMap<>();
        secondConcept.put("name", secondaryConcept);
        secondConcept.put("aliases", List.of("stub-" + slug(secondaryConcept)));
        secondConcept.put("definition", secondaryConcept + " helps explain how the source should be applied.");
        secondConcept.put("explanation", summarize(text));
        secondConcept.put("useCases", List.of("Knowledge traceability"));
        secondConcept.put("commonMisunderstandings", List.of("Assuming evidence can be omitted."));
        secondConcept.put("evidence", Map.of(
                "sourceId", sourceId == null ? 1L : sourceId,
                "quote", quote
        ));
        secondConcept.put("confidence", 0.71d);

        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("sourceName", primaryConcept);
        relation.put("targetName", secondaryConcept);
        relation.put("relationType", "SUPPORTS");
        relation.put("description", primaryConcept + " supports " + secondaryConcept + " in the source context.");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("concepts", List.of(firstConcept, secondConcept));
        payload.put("relations", List.of(relation));
        return toJson(payload);
    }

    private String extractSourceText(String prompt) {
        int marker = prompt.indexOf("原文:");
        if (marker < 0) {
            return "";
        }
        return prompt.substring(marker + "原文:".length()).trim();
    }

    private String extractField(String prompt, String field) {
        int start = prompt.indexOf(field);
        if (start < 0) {
            return null;
        }
        int valueStart = start + field.length();
        int end = prompt.indexOf('\n', valueStart);
        String raw = end < 0 ? prompt.substring(valueStart) : prompt.substring(valueStart, end);
        String normalized = raw.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private Long extractLongField(String prompt, String field) {
        String value = extractField(prompt, field);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String summarize(String text) {
        String first = firstSentence(text);
        String second = secondSentence(text);
        String summary = defaultIfBlank(first, text);
        if (second != null && !second.equals(first)) {
            summary = summary + " " + second;
        }
        return clip(summary, 280);
    }

    private String firstSentence(String text) {
        return sentenceAt(text, 0);
    }

    private String secondSentence(String text) {
        return sentenceAt(text, 1);
    }

    private String sentenceAt(String text, int index) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.trim().split("(?<=[。！？.!?])\\s+|\\n+");
        if (index < parts.length && !parts[index].isBlank()) {
            return parts[index].trim();
        }
        return parts.length == 0 ? null : parts[0].trim();
    }

    private String detectPrimaryConcept(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        if (lower.contains("websocket") && lower.contains("resume")) {
            return "WebSocket Resume";
        }
        if (lower.contains("citation")) {
            return "Citation Grounding";
        }
        if (lower.contains("token") && lower.contains("refresh")) {
            return "Token Refresh";
        }
        return "Research Insight";
    }

    private String slug(String value) {
        return value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String clip(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength).trim();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.LLM_CALL_FAILED, "Failed to serialize stub payload");
        }
    }
}
