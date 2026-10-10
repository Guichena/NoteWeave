package com.noteweave.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Shadow extraction of explicit user instructions; no assistant text becomes a constraint. */
public final class ConversationConstraintProjectorV2 {
    public static final String RULE_VERSION = "constraint-projector-v2-a2";
    /** 长期生效的措辞。 */
    private static final java.util.regex.Pattern DURABLE = java.util.regex.Pattern.compile(
            "以后|今后|往后|之后都|从现在起|从今以后|每次|每一次|一律|始终|总是|一直都?要");
    private static final java.util.regex.Pattern LANGUAGE = java.util.regex.Pattern.compile(
            "(?:改用|改成|换成|使用|用)(简体中文|繁体中文|中文|英文|英语|日文|日语|韩文)");
    private static final java.util.regex.Pattern FORMAT_CUE = java.util.regex.Pattern.compile(
            "先给结论|结论先行|先说结论|用表格|表格对比|分点|分条|列表|列出|不超过\\d+字|控制在\\d+字|"
                    + "字数|简短|简洁|精简|详细一点|展开讲|附上(?:来源|引用|出处)|标注(?:来源|出处)|带上代码|给出代码");
    private static final java.util.regex.Pattern NEGATION = java.util.regex.Pattern.compile(
            "不要|别再|不许|禁止|避免");
    /** "那个格式""跟之前一样"这类没有说清指什么的指代。 */
    private static final java.util.regex.Pattern VAGUE_REFERENCE = java.util.regex.Pattern.compile(
            "那个格式|那种格式|之前那样|上次那样|跟之前一样|和之前一样|原来那样|照旧");
    private static final java.util.regex.Pattern LEADING_FILLER = java.util.regex.Pattern.compile(
            "^(?:更正|补充|另外|还有|请|记住|记得|以后|今后|往后|从现在起|从今以后|每次|一律|始终|总是)+[：:，,\\s]*");

    public List<Constraint> project(List<TopicSegmenterV2.Message> messages) {
        List<Constraint> output = new ArrayList<>();
        Map<String, Integer> activeByKind = new HashMap<>();
        List<TopicSegmenterV2.Segment> segments = new TopicSegmenterV2().segment(messages).segments();
        for (TopicSegmenterV2.Message message : messages) {
            if (!"USER".equals(message.role())) continue;
            String value = message.text().trim();
            Constraint next = explicit(message, value);
            if (next == null) continue;
            String topicId = segments.stream()
                    .filter(segment -> segment.startSeq() <= message.seq()
                            && message.seq() <= segment.endSeq())
                    .findFirst().map(TopicSegmenterV2.Segment::topicId).orElse("");
            String key = next.kind() + ":" + next.scope()
                    + ("CURRENT_TOPIC".equals(next.scope()) ? ":" + topicId : "");
            Integer previous = activeByKind.get(key);
            if (previous != null && value.matches("(?s).*(更正|改用|不要).*")
                    && !"UNRESOLVED".equals(next.status())) {
                Constraint old = output.get(previous);
                output.set(previous, new Constraint(old.id(), old.sourceMessageId(), old.kind(),
                        old.scope(), old.text(), old.validFromSeq(), message.seq(),
                        "REVOKED", next.id()));
            }
            output.add(next);
            if ("ACTIVE".equals(next.status())) activeByKind.put(key, output.size() - 1);
        }
        return List.copyOf(output);
    }

    /**
     * 识别一条用户消息里的显式要求，每条消息最多产出一条约束，优先级：指代不明 > 语言 > 格式 > 否定。
     * 带"以后""每次""从现在起"等长期措辞的要求在整个会话内生效，否则只在当前话题内生效。
     */
    private Constraint explicit(TopicSegmenterV2.Message message, String text) {
        String kind;
        String ruleText;
        String status = "ACTIVE";
        java.util.regex.Matcher language;
        java.util.regex.Matcher format;
        java.util.regex.Matcher negation;
        if (VAGUE_REFERENCE.matcher(text).find()) {
            // 指向前文某种格式但没有说清是哪种，不能直接生效
            kind = "FORMAT";
            ruleText = "";
            status = "UNRESOLVED";
        } else if ((language = lastPositiveLanguage(text)) != null) {
            kind = "LANGUAGE";
            ruleText = language.group(1);
        } else if ((format = FORMAT_CUE.matcher(text)).find()) {
            kind = "FORMAT";
            ruleText = clause(text, format.start());
        } else if ((negation = NEGATION.matcher(text)).find()) {
            kind = "NEGATION";
            ruleText = clause(text, negation.start());
        } else {
            return null;
        }
        String scope = DURABLE.matcher(text).find() ? "CONVERSATION" : "CURRENT_TOPIC";
        String id = "constraint-" + digest(message.messageId() + ":" + kind).substring(0, 24);
        return new Constraint(id, message.messageId(), kind, scope, ruleText,
                message.seq(), null, status, null);
    }

    /** 最后一处没有被"不要""别"否定的"用某种语言"。 */
    private static java.util.regex.Matcher lastPositiveLanguage(String text) {
        java.util.regex.Matcher matcher = LANGUAGE.matcher(text);
        java.util.regex.Matcher found = null;
        while (matcher.find()) {
            String before = text.substring(Math.max(0, matcher.start() - 2), matcher.start());
            if (before.endsWith("不要") || before.endsWith("别") || before.endsWith("不")) continue;
            found = LANGUAGE.matcher(text);
            found.find(matcher.start());
        }
        return found;
    }

    /** 命中位置所在的分句，去掉开头的长期措辞和"请""记住"等客套，作为约束原文。 */
    private static String clause(String text, int position) {
        int start = position;
        while (start > 0 && "，,。；;！!？?\n：:".indexOf(text.charAt(start - 1)) < 0) start--;
        int end = position;
        while (end < text.length() && "，,。；;！!？?\n".indexOf(text.charAt(end)) < 0) end++;
        String clause = text.substring(start, end).trim();
        return LEADING_FILLER.matcher(clause).replaceFirst("").trim();
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record Constraint(String id, String sourceMessageId, String kind, String scope,
                             String text, int validFromSeq, Integer invalidAfterSeq,
                             String status, String supersededBy) {}
}
