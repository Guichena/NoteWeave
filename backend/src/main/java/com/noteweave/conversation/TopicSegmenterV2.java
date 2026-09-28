package com.noteweave.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, conservative topic projection over a frozen message sequence. */
public final class TopicSegmenterV2 {
    public static final String RULE_VERSION = "topic-segmenter-v2-a1";
    private static final Pattern TERMS = Pattern.compile("[\\p{IsHan}]{2,}|[a-z0-9]{3,}");
    private static final Pattern RETURN = Pattern.compile("^(回到|再谈|接着谈|继续讨论)");
    private static final Pattern SWITCH = Pattern.compile("^(改聊|换个话题|另外聊|现在解释|现在谈)");
    private static final Pattern CONTINUE = Pattern.compile("^(更正|补充|继续|那|这|第二|第三|上一)");
    private static final Pattern FOLLOW_UP_QUESTION = Pattern.compile("什么时候|为什么|为何");
    private static final Set<String> STOP_TERMS = Set.of(
            "怎么", "什么", "这个", "那个", "一下", "问题", "现在", "继续", "之前", "可以", "请问", "我们", "我的");

    public Projection segment(List<Message> messages) {
        List<Segment> segments = new ArrayList<>();
        Map<String, String> anchors = new LinkedHashMap<>();
        int previousSeq = 0;
        for (Message message : messages) {
            if (message.seq() <= previousSeq || message.messageId().isBlank()
                    || !Set.of("USER", "ASSISTANT").contains(message.role())) {
                throw new IllegalArgumentException("topic messages must be ordered and identified");
            }
            previousSeq = message.seq();
            Segment active = segments.isEmpty() ? null : segments.get(segments.size() - 1);
            Choice choice = choose(message, active, anchors);
            if (active != null && active.topicId().equals(choice.topicId())
                    && active.endSeq() + 1 == message.seq()) {
                segments.set(segments.size() - 1, new Segment(active.segmentId(), active.topicId(),
                        active.startSeq(), message.seq(),
                        "UNCERTAIN".equals(choice.status()) ? "UNCERTAIN" : active.status(),
                        choice.reason(), RULE_VERSION));
            } else {
                String segmentId = "segment-" + digest(choice.topicId() + ":" + message.seq()).substring(0, 24);
                segments.add(new Segment(segmentId, choice.topicId(), message.seq(), message.seq(),
                        choice.status(), choice.reason(), RULE_VERSION));
            }
            if (choice.newTopic()) anchors.put(choice.topicId(), message.text());
        }
        return new Projection(List.copyOf(segments));
    }

    private Choice choose(Message message, Segment active, Map<String, String> anchors) {
        if (active == null) return fresh(message, "FIRST_MESSAGE", "CONFIDENT");
        if (!"USER".equals(message.role()) || message.text().isBlank()) {
            return new Choice(active.topicId(), "ASSISTANT_OR_EMPTY", active.status(), false);
        }
        String text = message.text().trim();
        if (RETURN.matcher(text).find()) {
            String previous = bestMatchingTopic(text, anchors);
            if (previous != null) return new Choice(previous, "EXPLICIT_TOPIC_RETURN", "CONFIDENT", false);
            return fresh(message, "UNRESOLVED_TOPIC_RETURN", "UNCERTAIN");
        }
        if (SWITCH.matcher(text).find()) return fresh(message, "EXPLICIT_TOPIC_SWITCH", "CONFIDENT");
        if (CONTINUE.matcher(text).find() || FOLLOW_UP_QUESTION.matcher(text).find()) {
            return new Choice(active.topicId(), "EXPLICIT_CONTINUATION", active.status(), false);
        }
        String matched = bestMatchingTopic(text, anchors);
        if (matched != null) {
            return new Choice(matched, matched.equals(active.topicId())
                    ? "ACTIVE_ANCHOR_MATCH" : "PRIOR_ANCHOR_MATCH", "CONFIDENT", false);
        }
        String activeAnchor = anchors.getOrDefault(active.topicId(), "");
        if (text.codePointCount(0, text.length()) <= 8 && !activeAnchor.startsWith("以后")) {
            return new Choice(active.topicId(), "SHORT_FOLLOW_UP", active.status(), false);
        }
        return fresh(message, "NO_MATCHING_TOPIC_ANCHOR", "UNCERTAIN");
    }

    private String bestMatchingTopic(String text, Map<String, String> anchors) {
        Set<String> query = terms(text);
        String best = null;
        int bestScore = 0;
        for (Map.Entry<String, String> entry : anchors.entrySet()) {
            int score = 0;
            for (String term : terms(entry.getValue())) {
                if (!STOP_TERMS.contains(term) && query.contains(term)) score++;
            }
            if (score > bestScore) {
                best = entry.getKey();
                bestScore = score;
            }
        }
        return best;
    }

    private Set<String> terms(String text) {
        java.util.HashSet<String> values = new java.util.HashSet<>();
        Matcher matcher = TERMS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String term = matcher.group();
            values.add(term);
            if (term.codePoints().allMatch(codepoint -> Character.UnicodeScript.of(codepoint)
                    == Character.UnicodeScript.HAN)) {
                int[] points = term.codePoints().toArray();
                for (int index = 0; index < points.length - 1; index++) {
                    values.add(new String(points, index, 2));
                }
            }
        }
        return values;
    }

    private Choice fresh(Message message, String reason, String status) {
        return new Choice("topic-" + digest(message.messageId()).substring(0, 24), reason, status, true);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record Message(String messageId, int seq, String role, String text) {}
    public record Segment(String segmentId, String topicId, int startSeq, int endSeq,
                          String status, String reason, String ruleVersion) {}
    public record Projection(List<Segment> segments) {}
    private record Choice(String topicId, String reason, String status, boolean newTopic) {}
}
