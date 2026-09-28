package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** C4 offline selection baseline from the fixed C0 annotations; no production flag is enabled. */
class ContextGoldReplayV2Test {
    private final ContextWindowPlannerV2 planner = new ContextWindowPlannerV2();

    @Test
    void fixedGoldCasesKeepContinuousRawAndApprovedControls() throws Exception {
        JsonNode gold;
        try (var input = getClass().getResourceAsStream("/conversation/context-v2-gold-v1.json")) {
            gold = new ObjectMapper().readTree(input);
        }
        int replayed = 0;
        int rawMismatches = 0;
        int constraintMismatches = 0;
        int memoryMismatches = 0;
        int summaryMismatches = 0;
        for (JsonNode entry : gold.path("cases")) {
            if ("DELETION_AND_REDACTION".equals(entry.path("scenario").asText())) continue;
            ContextProjectionV2 projection = planner.compile(input(entry));
            JsonNode expected = entry.path("expected");
            List<String> expectedRaw = strings(expected.path("raw_tail_ids"));
            List<String> expectedConstraints = strings(expected.path("include_constraint_ids"));
            List<String> expectedMemories = strings(expected.path("include_memory_revision_ids"));
            if (!expectedRaw.equals(projection.rawTail().stream()
                    .map(ContextProjectionV2.RawMessage::messageId).toList())) rawMismatches++;
            if (!expectedConstraints.equals(projection.constraints().stream()
                    .map(ContextProjectionV2.UserConstraint::constraintId).toList())) constraintMismatches++;
            if (!expectedMemories.equals(projection.memoryRevisions().stream()
                    .map(ContextProjectionV2.MemoryRevision::revisionId).toList())) memoryMismatches++;
            int rawStart = projection.rawTail().get(0).seq();
            List<String> expectedSummaries = strings(expected.path("include_topic_segment_ids"))
                    .stream().filter(id -> summaryEnd(id) < rawStart).toList();
            if (!expectedSummaries.equals(projection.topicSummaries().stream()
                    .map(ContextProjectionV2.TopicSummary::segmentId).toList())) summaryMismatches++;
            assertThat(projection.selectedTokens()).isLessThanOrEqualTo(projection.tokenBudget());
            replayed++;
        }
        assertThat(replayed).isEqualTo(5);
        assertThat(rawMismatches).isZero();
        assertThat(constraintMismatches).isZero();
        assertThat(memoryMismatches).isZero();
        assertThat(summaryMismatches).isZero();
    }

    private ContextWindowPlannerV2.Input input(JsonNode entry) {
        List<ContextProjectionV2.RawMessage> messages = new ArrayList<>();
        List<TopicSegmenterV2.Segment> segments = new ArrayList<>();
        String topic = null;
        int start = 0;
        JsonNode last = null;
        for (JsonNode message : entry.path("messages")) {
            String id = message.path("id").asText();
            int seq = message.path("seq").asInt();
            String text = message.path("text").asText();
            messages.add(new ContextProjectionV2.RawMessage(id, seq, "USER", text, sha256(text)));
            String nextTopic = message.path("topic").asText();
            if (topic != null && !topic.equals(nextTopic)) {
                segments.add(segment(topic, start, seq - 1));
                start = seq;
            } else if (topic == null) {
                start = seq;
            }
            topic = nextTopic;
            last = message;
        }
        segments.add(segment(topic, start, messages.size()));
        String scenario = entry.path("scenario").asText();
        String purpose = switch (scenario) {
            case "NEGATION_AND_CORRECTION" -> "CURRENT_REPORT";
            case "CROSS_TOPIC_FORMAT_CONSTRAINT" -> "TECHNICAL_ANSWERS";
            default -> "QA";
        };
        List<ContextProjectionV2.UserConstraint> constraints = new ArrayList<>();
        for (JsonNode rule : entry.path("constraints")) {
            String sourceId = rule.path("source_message_id").asText();
            int seq = messages.stream().filter(message -> sourceId.equals(message.messageId()))
                    .mapToInt(ContextProjectionV2.RawMessage::seq).findFirst().orElseThrow();
            constraints.add(new ContextProjectionV2.UserConstraint(rule.path("id").asText(), sourceId,
                    "USER", "FORMAT", rule.path("scope").asText(), rule.path("id").asText(),
                    seq, null, rule.path("status").asText()));
        }
        List<ContextProjectionV2.MemoryRevision> memories = new ArrayList<>();
        for (JsonNode item : entry.path("memory_revisions")) {
            String revisionId = item.path("id").asText();
            String value = "approved control: " + revisionId;
            memories.add(new ContextProjectionV2.MemoryRevision("memory-" + revisionId,
                    revisionId, value, sha256(value)));
        }
        List<ContextProjectionV2.TopicSummary> ready = new ArrayList<>();
        JsonNode expected = entry.path("expected");
        for (String id : strings(expected.path("include_topic_segment_ids"))) {
            addReadySummary(ready, segments, id);
        }
        for (String id : strings(expected.path("exclude_topic_segment_ids"))) {
            addReadySummary(ready, segments, id);
        }
        return new ContextWindowPlannerV2.Input("workspace", "actor", "conversation",
                messages.size(), last.path("text").asText(), purpose, 10_000,
                messages, segments, ready, constraints, memories);
    }

    private static void addReadySummary(List<ContextProjectionV2.TopicSummary> ready,
                                        List<TopicSegmenterV2.Segment> segments, String id) {
        segments.stream().filter(segment -> id.equals(segment.segmentId())).findFirst()
                .ifPresent(segment -> {
                    String text = "summary for " + id;
                    ready.add(new ContextProjectionV2.TopicSummary(segment.topicId(), id,
                            "revision-" + id, segment.startSeq(), segment.endSeq(), text, sha256(text)));
                });
    }

    private static int summaryEnd(String id) {
        return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
    }

    private static TopicSegmenterV2.Segment segment(String topic, int start, int end) {
        return new TopicSegmenterV2.Segment(topic + "-" + start + "-" + end, topic,
                start, end, "CONFIDENT", "GOLD_ANNOTATION", TopicSegmenterV2.RULE_VERSION);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) values.add(item.asText());
        return values;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
