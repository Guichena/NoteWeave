package com.noteweave.conversation;

import com.noteweave.chat.ChatLlmClient;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 判断不确定的话题边界应并入上一段、确认为新话题，还是回到更早的话题。
 * 模型不可用或两次输出都不合规时保持未复核，不允许当成已经确认。
 */
final class TopicBoundaryJudge {
    static final String NOT_RUN = "TOPIC_BOUNDARY_REVIEW_NOT_RUN";
    private static final Logger log = LoggerFactory.getLogger(TopicBoundaryJudge.class);
    private static final Pattern DECISION_FIELD = Pattern.compile(
            "\"decision\"\\s*:\\s*\"(SAME|NEW|RETURN)\"");
    private static final Pattern TOPIC_INDEX_FIELD = Pattern.compile(
            "\"topic_index\"\\s*:\\s*(\\d+)");
    private static final String PROMPT = """
            判断这条新消息相对已有话题应如何切分。
            只输出一个 JSON 对象。decision 取 SAME、NEW、RETURN 之一。
            SAME 表示与紧邻的上一个话题相同。NEW 表示确定是新话题。RETURN 表示回到更早的某个话题，并给出 topic_index。
            topic_index 从 0 开始，只统计更早且不同于当前话题的话题，按出现顺序编号。
            不要输出其他文字。""";

    private TopicBoundaryJudge() {
    }

    static Decision judge(String userPrompt, ChatLlmClient client) {
        if (client == null || !client.isEnabled()) {
            return Decision.unavailable();
        }
        String user = userPrompt == null ? "" : userPrompt;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String output = client.streamChat(PROMPT, user, 120, token -> { });
                Decision parsed = parse(output);
                if (parsed != null) {
                    return parsed;
                }
                user = user + "\n上次输出不合规，只输出 JSON，decision 取 SAME、NEW、RETURN。RETURN 还要给出 topic_index。";
            } catch (RuntimeException ex) {
                log.warn("话题边界复核失败: {}", ex.getMessage());
                user = user + "\n上次调用失败，只输出 JSON，decision 取 SAME、NEW、RETURN。RETURN 还要给出 topic_index。";
            }
        }
        return Decision.unavailable();
    }

    static Decision parse(String output) {
        if (output == null) {
            return null;
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        String json = output.substring(start, end + 1);
        Matcher decision = DECISION_FIELD.matcher(json);
        if (!decision.find()) {
            return null;
        }
        String value = decision.group(1);
        if ("RETURN".equals(value)) {
            Matcher index = TOPIC_INDEX_FIELD.matcher(json);
            if (!index.find()) {
                return null;
            }
            return new Decision(value, Integer.parseInt(index.group(1)), null);
        }
        return new Decision(value, null, null);
    }

    record Decision(String decision, Integer topicIndex, String degradationReason) {
        static Decision unavailable() {
            return new Decision(null, null, NOT_RUN);
        }

        boolean skipped() {
            return degradationReason != null;
        }
    }
}
