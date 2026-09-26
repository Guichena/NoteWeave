package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContextProjectionV2ContractTest {

    @Test
    void frozenProjectionKeepsTopicAndMemoryReferencesSeparateFromSourceEvidence() throws Exception {
        ContextProjectionV2 projection = new ContextProjectionV2(
                "context-projection-v2", "topic-compiler-v2-dev", "workspace-1", "actor-1", "conversation-1",
                11, "回到缓存一致性，第二种写入顺序呢？",
                List.of(new ContextProjectionV2.RawMessage("a11", 11, "USER", "回到缓存一致性",
                        digest("回到缓存一致性"))),
                List.of(new ContextProjectionV2.TopicSummary("cache", "cache-1-2", "summary-rev-1",
                        1, 2, "缓存写入顺序", digest("缓存写入顺序"))),
                List.of(new ContextProjectionV2.UserConstraint("format-1", "a1", "USER",
                        "FORMAT", "CONVERSATION", "先给结论", 1, null, "ACTIVE")),
                List.of(new ContextProjectionV2.MemoryRevision("memory-1", "memory-rev-2",
                        "简洁回答", digest("简洁回答"))),
                List.of(new ContextProjectionV2.Decision("TOPIC_SUMMARY", "summary-rev-1",
                        "INCLUDE", "RETURN_TO_CACHE_TOPIC")),
                1000, 120, List.of(), "FULL");

        assertThat(projection.topicSummaries()).extracting(ContextProjectionV2.TopicSummary::topicId)
                .containsExactly("cache");
        assertThat(projection.memoryRevisions()).extracting(ContextProjectionV2.MemoryRevision::revisionId)
                .containsExactly("memory-rev-2");
        assertThat(java.util.Arrays.stream(projection.getClass().getRecordComponents())
                .noneMatch(component -> component.getName().toLowerCase().contains("citation")
                        || component.getName().toLowerCase().contains("evidence"))).isTrue();
    }

    @Test
    void frozenProjectionRejectsFutureRefsAndLeakedRedactedText() throws Exception {
        ContextProjectionV2.RawMessage future = new ContextProjectionV2.RawMessage(
                "future", 12, "USER", "future content", digest("future content"));
        assertThatThrownBy(() -> projection(List.of(future), List.of(), "FULL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cutoff");
        ContextProjectionV2.RawMessage current = new ContextProjectionV2.RawMessage(
                "current", 11, "USER", "private content", digest("private content"));
        assertThatThrownBy(() -> projection(List.of(current), List.of(), "METADATA_ONLY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redacted");
        assertThatThrownBy(() -> new ContextProjectionV2.UserConstraint(
                "constraint-1", "message-1", "ASSISTANT", "FORMAT", "GLOBAL",
                "use bullets", 1, null, "ACTIVE"))
                .isInstanceOf(IllegalArgumentException.class);
        ContextProjectionV2 redacted = projection(List.of(new ContextProjectionV2.RawMessage(
                "current", 11, "USER", "", digest(""))), List.of(), "METADATA_ONLY");
        assertThat(redacted.rawTail().get(0).text()).isEmpty();
    }

    @Test
    void goldCasesCoverRequiredTopicAndDeletionScenariosWithValidAnnotations() throws Exception {
        JsonNode gold;
        try (var input = getClass().getResourceAsStream("/conversation/context-v2-gold-v1.json")) {
            gold = new ObjectMapper().readTree(input);
        }
        assertThat(gold.path("schema_version").asText()).isEqualTo("context-v2-gold-v1");
        Set<String> scenarios = new HashSet<>();
        for (JsonNode entry : gold.path("cases")) {
            scenarios.add(entry.path("scenario").asText());
            JsonNode messages = entry.path("messages");
            JsonNode rawTail = entry.path("expected").path("raw_tail_ids");
            assertThat(messages.isArray()).isTrue();
            assertThat(rawTail.isArray()).isTrue();
            Set<String> knownIds = new HashSet<>();
            int previousSeq = 0;
            for (JsonNode message : messages) {
                assertThat(message.path("seq").asInt()).isEqualTo(++previousSeq);
                assertThat(knownIds.add(message.path("id").asText())).isTrue();
            }
            int previousTailSeq = 0;
            for (JsonNode id : rawTail) {
                assertThat(knownIds).contains(id.asText());
                int seq = 0;
                for (JsonNode message : messages) {
                    if (id.asText().equals(message.path("id").asText())) seq = message.path("seq").asInt();
                }
                if (previousTailSeq != 0) assertThat(seq).isEqualTo(previousTailSeq + 1);
                previousTailSeq = seq;
            }
            assertThat(previousTailSeq).isEqualTo(previousSeq);
            Set<String> constraintIds = new HashSet<>();
            for (JsonNode constraint : entry.path("constraints")) {
                constraintIds.add(constraint.path("id").asText());
                assertThat(knownIds).contains(constraint.path("source_message_id").asText());
            }
            for (String field : List.of("include_constraint_ids", "exclude_constraint_ids")) {
                for (JsonNode id : entry.path("expected").path(field)) {
                    assertThat(constraintIds).contains(id.asText());
                }
            }
            Set<String> memoryRevisionIds = new HashSet<>();
            for (JsonNode memory : entry.path("memory_revisions")) {
                assertThat(memory.path("review_status").asText()).isEqualTo("APPROVED");
                memoryRevisionIds.add(memory.path("id").asText());
            }
            for (JsonNode id : entry.path("expected").path("include_memory_revision_ids")) {
                assertThat(memoryRevisionIds).contains(id.asText());
            }
        }
        assertThat(scenarios).containsExactlyInAnyOrder(
                "A_TO_B_TO_A", "SIMILAR_ENTITIES_DISTINCT_TOPIC", "NEGATION_AND_CORRECTION",
                "CROSS_TOPIC_FORMAT_CONSTRAINT", "SUMMARY_LAG", "DELETION_AND_REDACTION");
    }

    private ContextProjectionV2 projection(List<ContextProjectionV2.RawMessage> messages,
                                           List<ContextProjectionV2.TopicSummary> summaries,
                                           String availability) {
        return new ContextProjectionV2("context-projection-v2", "topic-compiler-v2-dev",
                "workspace-1", "actor-1", "conversation-1", 11,
                "METADATA_ONLY".equals(availability) ? "" : "current input",
                messages, summaries, List.of(), List.of(), List.of(), 1000, 100,
                List.of(), availability);
    }

    private String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
