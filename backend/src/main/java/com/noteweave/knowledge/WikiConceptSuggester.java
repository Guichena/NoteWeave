package com.noteweave.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 为资料挑选值得在知识库中单独成页的概念。
 * <p>
 * 大模型可用时，根据标题、摘要和开头的正文挑出 3 到 5 个核心术语；否则使用标签和标题的规则抽取。
 * 两条路径的结果都经过同一套过滤：去掉文件格式、纯数字和时间戳、资料类型标签以及像句子的长片段，
 * 避免把文件名碎片或正文里的半句话建成概念页。
 */
@Component
public class WikiConceptSuggester {

    static final int MAX_CONCEPTS = 5;
    private static final int MAX_CONCEPT_CHARS = 12;
    private static final int PROMPT_TEXT_CHARS = 1_600;
    private static final Pattern DIGITS_OR_TIME = Pattern.compile("^[\\d:：.\\-_/\\s]+$");
    private static final Pattern SENTENCE_MARKS = Pattern.compile("[，。；！？,;!?、]");
    private static final Pattern SNAKE_TAG = Pattern.compile("^[a-z]+(_[a-z]+)+$");
    private static final List<String> FORMAT_WORDS = List.of(
            "markdown", "pdf", "txt", "text", "doc", "docx", "md", "wav", "mp3", "m4a", "mp4", "csv", "json");

    private static final String SYSTEM_PROMPT = """
            你为个人知识库挑选值得单独成页的概念。
            只挑资料里反复出现或处于核心位置的术语、方法、组件或主题名词短语，每个 2 到 12 个字。
            不要文件名、文件格式、日期、数字、人名、完整句子或泛泛的词（例如：资料、内容、问题）。
            挑 3 到 5 个，按重要性排序。只输出 JSON 字符串数组，例如 ["混合检索", "RRF 融合"]。""";

    private static final Logger log = LoggerFactory.getLogger(WikiConceptSuggester.class);

    private final ChatLlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public WikiConceptSuggester(ChatLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** 不接大模型的规则抽取，供测试和未注入依赖的场景使用。 */
    static WikiConceptSuggester ruleBasedOnly() {
        return new WikiConceptSuggester(null);
    }

    public List<String> suggest(String title, String summary, String tagsJson, String leadingText) {
        return suggest(title, summary, tagsJson, leadingText, List.of());
    }

    /** existingConcepts 是工作台已有的概念页标题，含义相同时让模型沿用原标题。 */
    public List<String> suggest(String title, String summary, String tagsJson, String leadingText,
                                List<String> existingConcepts) {
        if (llmClient != null && llmClient.isEnabled()) {
            try {
                String output = llmClient.streamChat(SYSTEM_PROMPT,
                        prompt(title, summary, leadingText, existingConcepts), 200, token -> { });
                List<String> concepts = filter(parse(output));
                if (!concepts.isEmpty()) return concepts;
                log.info("LLM suggested no usable Wiki concepts for {}; falling back to rules", title);
            } catch (RuntimeException ex) {
                log.warn("LLM Wiki concept suggestion failed for {}: {}", title, ex.getMessage());
            }
        }
        return filter(WikiConceptExtractor.extract(title, tagsJson));
    }

    static List<String> filter(List<String> candidates) {
        List<String> accepted = new ArrayList<>();
        for (String candidate : candidates) {
            String value = candidate == null ? "" : candidate.trim();
            String lowered = value.toLowerCase(Locale.ROOT);
            if (value.codePointCount(0, value.length()) < 2
                    || value.codePointCount(0, value.length()) > MAX_CONCEPT_CHARS
                    || DIGITS_OR_TIME.matcher(value).matches()
                    || SENTENCE_MARKS.matcher(value).find()
                    || SNAKE_TAG.matcher(value).matches()
                    || FORMAT_WORDS.contains(lowered)
                    || accepted.stream().anyMatch(item -> item.equalsIgnoreCase(value))) {
                continue;
            }
            accepted.add(value);
            if (accepted.size() == MAX_CONCEPTS) break;
        }
        return List.copyOf(accepted);
    }

    private List<String> parse(String output) {
        if (output == null) return List.of();
        int start = output.indexOf('[');
        int end = output.lastIndexOf(']');
        if (start < 0 || end < start) return List.of();
        try {
            JsonNode array = objectMapper.readTree(output.substring(start, end + 1));
            List<String> values = new ArrayList<>();
            array.forEach(node -> values.add(node.asText("")));
            return values;
        } catch (Exception ex) {
            return List.of();
        }
    }

    static String prompt(String title, String summary, String leadingText, List<String> existingConcepts) {
        String text = leadingText == null ? "" : leadingText;
        String existing = existingConcepts == null || existingConcepts.isEmpty() ? ""
                : "\n\n工作台已有的概念页：" + String.join("、", existingConcepts)
                        + "\n含义相同的概念请直接使用上面的原标题，不要改写空格、大小写或措辞。";
        return "资料标题：" + (title == null ? "" : title) + "\n\n资料摘要：" + (summary == null ? "" : summary)
                + "\n\n正文开头：\n" + (text.length() > PROMPT_TEXT_CHARS ? text.substring(0, PROMPT_TEXT_CHARS) : text)
                + existing;
    }
}
