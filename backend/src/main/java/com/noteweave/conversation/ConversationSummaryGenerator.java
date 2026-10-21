package com.noteweave.conversation;

import com.noteweave.chat.ChatLlmClient;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 生成会话摘要。
 * <p>
 * 大模型可用时，在上一版摘要的基础上只读入新增的消息，输出更新后的完整摘要（增量摘要）；
 * 没有上一版摘要时对这一段消息做一次完整摘要。大模型未配置、调用失败或输出为空时，
 * 退回抽取式摘要：上一版摘要加上新增消息的原文，保证摘要任务始终能完成。
 * <p>
 * 超长消息不会被静默丢尾：提示词里按 {@code MAX_MESSAGE_CHARS_IN_PROMPT} 分块全部拼入，
 * 抽取式摘要超限时保留首尾并显式标注 {@link #TRUNCATION_MARKER}。
 */
@Component
public class ConversationSummaryGenerator {

    public static final String METHOD_LLM_INCREMENTAL = "LLM_INCREMENTAL";
    public static final String METHOD_LLM_FULL = "LLM_FULL";
    public static final String METHOD_EXTRACTIVE = "EXTRACTIVE";

    static final int MAX_SUMMARY_CHARS = 16_000;
    /** 文本被截断时追加的显式标记，避免半截原文被当成完整内容。 */
    static final String TRUNCATION_MARKER = "…（已截断）";
    private static final int MAX_LLM_SUMMARY_CHARS = 2_400;
    private static final int MAX_MESSAGE_CHARS_IN_PROMPT = 2_000;
    private static final int MAX_EXTRACTIVE_MESSAGE_CHARS = 600;
    private static final int SUMMARY_OUTPUT_TOKENS = 1_000;

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryGenerator.class);

    private static final String SYSTEM_PROMPT = """
            你负责维护一段对话的滚动摘要。摘要只在后续回答时用来回顾前文，不会展示给用户。
            要求：
            1. 保留用户的目标和问题、已经得出的结论、用户提出的约束和偏好、尚未解决的问题，以及关键的名称、数字和术语。
            2. 助手的回答只保留结论和关键依据，不保留引用编号、客套话和重复内容。
            3. 新增对话与已有摘要冲突时，以新增对话为准，并删除已被推翻的内容。
            4. 使用对话本身的语言，按要点分行书写，总长度不超过 800 字。
            5. 只输出摘要正文，不要添加标题或解释。""";

    private final ChatLlmClient llmClient;

    @Autowired
    public ConversationSummaryGenerator(ChatLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /** 不接大模型的抽取式生成器，供测试和未注入依赖的场景使用。 */
    public static ConversationSummaryGenerator extractiveOnly() {
        return new ConversationSummaryGenerator(null);
    }

    public Summary summarize(String baseSummary, List<Turn> newTurns) {
        String base = baseSummary == null ? "" : baseSummary.trim();
        List<Turn> turns = newTurns == null ? List.of() : newTurns;
        if (llmClient != null && llmClient.isEnabled() && !turns.isEmpty()) {
            try {
                String generated = llmClient.streamChat(SYSTEM_PROMPT, userPrompt(base, turns),
                        SUMMARY_OUTPUT_TOKENS, token -> { }).trim();
                if (!generated.isBlank()) {
                    return new Summary(bound(generated, MAX_LLM_SUMMARY_CHARS),
                            base.isBlank() ? METHOD_LLM_FULL : METHOD_LLM_INCREMENTAL);
                }
                log.warn("LLM returned an empty conversation summary; falling back to extractive summary");
            } catch (RuntimeException ex) {
                log.warn("LLM conversation summary failed; falling back to extractive summary: {}", ex.getMessage());
            }
        }
        return new Summary(extractive(base, turns), METHOD_EXTRACTIVE);
    }

    static String extractive(String base, List<Turn> turns) {
        StringBuilder builder = new StringBuilder(base == null ? "" : base.trim());
        for (Turn turn : turns) {
            String line = summarizeMessage(turn.role(), turn.content());
            if (line.isBlank()) continue;
            if (!builder.isEmpty()) builder.append('\n');
            builder.append(line);
        }
        return keepHeadAndTail(builder.toString(), MAX_SUMMARY_CHARS);
    }

    /** 抽取式摘要的单条消息格式：角色加上原文；超过 600 字符时保留首尾并标注已截断。 */
    public static String summarizeMessage(String role, String content) {
        String normalized = content == null ? "" : content.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isBlank()) return "";
        String bounded = keepHeadAndTail(normalized, MAX_EXTRACTIVE_MESSAGE_CHARS);
        return (role == null || role.isBlank() ? "message" : role.toLowerCase(Locale.ROOT))
                + ": " + bounded;
    }

    /**
     * 超长文本保留开头和结尾，中间换成截断标记，返回值长度不超过 {@code maximum}（标记计入额度）。
     * 尾部保留最新的内容，避免增量摘要丢掉刚刚发生的事。
     */
    static String keepHeadAndTail(String value, int maximum) {
        if (value.length() <= maximum) return value;
        int budget = Math.max(maximum - TRUNCATION_MARKER.length(), 0);
        int tail = budget / 3;
        int head = budget - tail;
        return value.substring(0, head) + TRUNCATION_MARKER
                + value.substring(value.length() - tail);
    }

    private static String userPrompt(String base, List<Turn> turns) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("已有摘要：\n").append(base.isBlank() ? "（无，这是第一次摘要）" : base).append("\n\n");
        prompt.append("新增对话：\n");
        for (Turn turn : turns) {
            String content = turn.content() == null ? "" : turn.content().trim();
            if (content.isBlank()) continue;
            prompt.append("ASSISTANT".equalsIgnoreCase(turn.role()) ? "助手：" : "用户：")
                    .append(promptContent(content)).append("\n");
        }
        prompt.append("\n请输出更新后的完整摘要。");
        return prompt.toString();
    }

    /** 超长消息按 2000 字符分块全部拼进提示词（块间标注序号），不再截掉尾部。 */
    static String promptContent(String content) {
        if (content.length() <= MAX_MESSAGE_CHARS_IN_PROMPT) return content;
        int chunks = (content.length() + MAX_MESSAGE_CHARS_IN_PROMPT - 1) / MAX_MESSAGE_CHARS_IN_PROMPT;
        StringBuilder builder = new StringBuilder(content.length() + chunks * 16);
        for (int index = 1, start = 0; start < content.length();
             index++, start += MAX_MESSAGE_CHARS_IN_PROMPT) {
            if (index > 1) {
                builder.append("\n（分块续 ").append(index).append('/').append(chunks).append("）\n");
            }
            builder.append(content, start, Math.min(content.length(), start + MAX_MESSAGE_CHARS_IN_PROMPT));
        }
        return builder.toString();
    }

    private static String bound(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    public record Turn(String role, String content) { }

    public record Summary(String text, String method) { }
}
