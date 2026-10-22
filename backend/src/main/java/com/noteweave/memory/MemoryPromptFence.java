package com.noteweave.memory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 记忆链路提示词里的数据围栏：用户消息与上一轮回答都包在 {@code <user_message>} /
 * {@code <previous_answer>} 里，并转义正文里出现的闭合标签。
 * <p>
 * 与约束抽取（{@code ConstraintOpsPrompt}）同一套写法：围栏只降低「正文里的指令被当成规则」的概率，
 * 真正的防线是解析侧的白名单与整批作废（见 {@code MemoryConversationExtractionService#parse}）。
 * 只转义闭合标签，不整体去尖括号，否则代码片段和比较运算符会被改坏。
 */
final class MemoryPromptFence {

    static final String USER_TAG = "user_message";
    static final String ANSWER_TAG = "previous_answer";
    static final String USER_OPEN = "<" + USER_TAG + ">";
    static final String USER_CLOSE = "</" + USER_TAG + ">";
    static final String ANSWER_OPEN = "<" + ANSWER_TAG + ">";
    static final String ANSWER_CLOSE = "</" + ANSWER_TAG + ">";

    /** 闭合标签允许空白与大小写差异，任何一种写法都要被转义，否则数据块可以被提前关掉。 */
    private static final Pattern CLOSING_TAG = Pattern.compile(
            "(?i)</\\s*(" + USER_TAG + "|" + ANSWER_TAG + ")\\s*>");

    private MemoryPromptFence() {
    }

    static String userMessage(String text) {
        return block(USER_TAG, text);
    }

    static String previousAnswer(String text) {
        return block(ANSWER_TAG, text);
    }

    static String block(String tag, String text) {
        return "<" + tag + ">\n" + escapeClosingTags(text) + "\n</" + tag + ">";
    }

    /** 转义正文里所有 {@code </user_message>}、{@code </previous_answer>}（含大小写/空白变体）。 */
    static String escapeClosingTags(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return CLOSING_TAG.matcher(text).replaceAll(match ->
                "&lt;/" + match.group(1).toLowerCase(Locale.ROOT) + "&gt;");
    }
}
