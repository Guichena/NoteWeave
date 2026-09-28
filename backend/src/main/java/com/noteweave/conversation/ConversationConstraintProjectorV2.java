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
    public static final String RULE_VERSION = "constraint-projector-v2-a1";

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

    private Constraint explicit(TopicSegmenterV2.Message message, String text) {
        String kind;
        String scope;
        String ruleText;
        String status = "ACTIVE";
        if (text.contains("以后") && text.contains("技术问题") && text.contains("先给结论")) {
            kind = "FORMAT";
            scope = "TECHNICAL_ANSWERS";
            ruleText = "先给结论";
        } else if (text.contains("改用中文") || text.contains("用中文")) {
            kind = "LANGUAGE";
            scope = "CURRENT_TOPIC";
            ruleText = "中文";
        } else if (text.contains("用英文")) {
            kind = "LANGUAGE";
            scope = "CURRENT_TOPIC";
            ruleText = "英文";
        } else if (text.contains("不要上一版")) {
            kind = "NEGATION";
            scope = "CURRENT_TOPIC";
            ruleText = "不要上一版";
        } else if (text.contains("那个格式")) {
            kind = "FORMAT";
            scope = "CURRENT_TOPIC";
            ruleText = "";
            status = "UNRESOLVED";
        } else {
            return null;
        }
        String id = "constraint-" + digest(message.messageId() + ":" + kind).substring(0, 24);
        return new Constraint(id, message.messageId(), kind, scope, ruleText,
                message.seq(), null, status, null);
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
