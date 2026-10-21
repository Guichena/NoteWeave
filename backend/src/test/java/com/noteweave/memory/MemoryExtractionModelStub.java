package com.noteweave.memory;

import com.noteweave.chat.ChatLlmClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 记忆抽取链路用的模型桩：分类器固定答 yes，抽取按提示词里的用户消息内容返回预置结果
 * （键是消息里必须出现的片段，命中第一条即返回）；反馈判断同样按消息内容返回预置 JSON，
 * 没预置时一律判成「不是对上一条回答的反馈、中性、方面未知」，与「沉默不算接受」一致。
 * 其余提示词（例如回答生成）一律返回空串，让调用方走各自的降级路径，不会发起任何网络调用。
 * <p>
 * 默认关闭，测试只在真正要抽取的那一步打开，保证同一次请求里的回答生成仍走模板回退。
 */
final class MemoryExtractionModelStub implements ChatLlmClient {

    /**
     * 分类器输出上限，与抽取输出的上限不同，用来区分两次模型调用。
     * 直接跟随分类器的真实预算，避免预算调整后桩把分类调用当成抽取调用。
     */
    static final int CLASSIFIER_TOKENS = MemoryExtractionClassifier.MAX_OUTPUT_TOKENS;
    /** 抽取提示词的开头，用来区分抽取调用和别的模型调用。 */
    static final String EXTRACTION_PROMPT_HEAD = "你从用户的一条消息中找出用户希望助手长期遵守的表达偏好";
    /** 反馈判断提示词的开头，与抽取提示词区分开。 */
    static final String JUDGE_PROMPT_HEAD = "判断这条用户消息是不是对上一条助手回答的反馈";
    static final String MESSAGE_MARKER = "用户消息：\n";
    /** 模型没抽到偏好时的终局回答。 */
    static final String NO_PREFERENCE = "[]";
    /** 反馈判断的默认结论：不是对上一条回答的反馈，中性，方面未知。 */
    static final String NEUTRAL_JUDGMENT = "{\"feedback\":false,\"polarity\":\"NEUTRAL\",\"aspect\":\"UNKNOWN\"}";
    /** 桩脚本值取这个标记时抛出异常，模拟调用失败。 */
    static final String THROW = "THROW";

    private final Map<String, String> outputs;
    private final Map<String, String> judgments;
    private volatile boolean enabled;

    MemoryExtractionModelStub(Map<String, String> outputsByMessageContent) {
        this(outputsByMessageContent, Map.of());
    }

    MemoryExtractionModelStub(Map<String, String> outputsByMessageContent,
                              Map<String, String> judgmentsByMessageContent) {
        this.outputs = new LinkedHashMap<>(outputsByMessageContent);
        this.judgments = new LinkedHashMap<>(judgmentsByMessageContent);
    }

    void enable() {
        this.enabled = true;
    }

    void disable() {
        this.enabled = false;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                             Consumer<String> onToken) {
        if (systemPrompt != null && systemPrompt.startsWith(EXTRACTION_PROMPT_HEAD)) {
            return lookup(outputs, messageOf(userPrompt), NO_PREFERENCE);
        }
        if (systemPrompt != null && systemPrompt.startsWith(JUDGE_PROMPT_HEAD)) {
            return lookup(judgments, messageOf(userPrompt), NEUTRAL_JUDGMENT);
        }
        if (maximumOutputTokens == CLASSIFIER_TOKENS) {
            return "yes";
        }
        return "";
    }

    private static String lookup(Map<String, String> script, String message, String fallback) {
        for (Map.Entry<String, String> entry : script.entrySet()) {
            if (message.contains(entry.getKey())) {
                String output = entry.getValue();
                if (THROW.equals(output)) {
                    throw new IllegalStateException("model unavailable");
                }
                return output;
            }
        }
        return fallback;
    }

    static String messageOf(String userPrompt) {
        String prompt = userPrompt == null ? "" : userPrompt;
        int index = prompt.lastIndexOf(MESSAGE_MARKER);
        return index < 0 ? prompt : prompt.substring(index + MESSAGE_MARKER.length());
    }
}
