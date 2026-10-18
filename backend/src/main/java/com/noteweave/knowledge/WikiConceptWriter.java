package com.noteweave.knowledge;

import com.noteweave.chat.ChatLlmClient;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 为自动概念页写概念摘要：根据提到这个概念的原文，用几句话说明它是什么、起什么作用、和哪些内容相关。
 *
 * <p>原来的概念页只有一句固定说明加原文片段，读者点进来看不到这个概念到底指什么。
 * 摘要只能使用给出的原文，依据的资料用 [[资料名]] 标注；模型不可用或输出不合格时返回空串，
 * 页面保留原来的固定说明。</p>
 */
@Component
public class WikiConceptWriter {

    static final int MAX_SUMMARY_CHARS = 600;
    private static final int MAX_EXCERPT_CHARS = 600;
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]]+)]]");
    private static final String SYSTEM_PROMPT = """
            你为个人知识库的概念页写概念摘要。根据给出的原文，用 2 到 4 句话说明这个概念是什么、起什么作用、和哪些内容相关。
            只使用原文里的信息，不补写原文没有的事实；依据某份资料的句子末尾用 [[资料名]] 标注，资料名必须与给出的完全一致。
            不要写标题、列表或说明这段文字是怎么生成的，直接输出摘要正文。""";
    private static final Logger log = LoggerFactory.getLogger(WikiConceptWriter.class);

    private final ChatLlmClient llmClient;

    @Autowired
    public WikiConceptWriter(ChatLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** 不接模型时始终返回空串，供测试和未注入依赖的场景使用。 */
    static WikiConceptWriter disabled() {
        return new WikiConceptWriter(null);
    }

    record Excerpt(String sourceTitle, String content) { }

    String summarize(String concept, List<Excerpt> excerpts) {
        if (llmClient == null || !llmClient.isEnabled() || excerpts == null || excerpts.isEmpty()) return "";
        StringBuilder prompt = new StringBuilder("概念：").append(concept).append("\n\n原文：\n");
        for (Excerpt excerpt : excerpts) {
            String text = excerpt.content() == null ? "" : excerpt.content().replaceAll("\\s+", " ").strip();
            prompt.append("[[").append(excerpt.sourceTitle()).append("]]：")
                    .append(text.length() <= MAX_EXCERPT_CHARS ? text : text.substring(0, MAX_EXCERPT_CHARS))
                    .append("\n");
        }
        try {
            String output = llmClient.streamChat(SYSTEM_PROMPT, prompt.toString(), 400, token -> { });
            return accepted(output, excerpts.stream().map(Excerpt::sourceTitle).toList());
        } catch (RuntimeException exception) {
            log.warn("Wiki concept summary failed for {}: {}", concept, exception.getMessage());
            return "";
        }
    }

    /** 摘要必须是一段正文，引用的资料名都在给出的资料里；否则不用。 */
    static String accepted(String output, List<String> sourceTitles) {
        String text = output == null ? "" : output.strip();
        if (text.isEmpty() || text.length() > MAX_SUMMARY_CHARS || text.startsWith("#")) return "";
        var matcher = WIKI_LINK.matcher(text);
        while (matcher.find()) {
            if (!sourceTitles.contains(matcher.group(1).strip())) return "";
        }
        return text.replace("\n", " ");
    }
}
