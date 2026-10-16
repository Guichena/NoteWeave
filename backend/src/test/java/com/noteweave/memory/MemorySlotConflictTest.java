package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.ChatLlmClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class MemorySlotConflictTest {

    @Test
    void contractSentencesStayOnDifferentSlots() {
        MemorySlots.Assignment conclusion = MemorySlots.complete(
                MemorySlots.categoryOf("回答先给结论，再展开证据"),
                "回答先给结论，再展开证据", "PREFERENCE", null, null, null);
        MemorySlots.Assignment longer = MemorySlots.complete(
                MemorySlots.categoryOf("用户可能偏好更长的解释"),
                "用户可能偏好更长的解释", "PREFERENCE", null, null, null);
        MemorySlots.Assignment order = MemorySlots.complete(
                "structure", "以后回答先给结论再展开", "PREFERENCE", null, null, null);
        MemorySlots.Assignment emoji = MemorySlots.complete(
                "forbidden", "以后不要使用表情符号", "PREFERENCE", null, null, null);

        assertThat(conclusion.slot()).isEqualTo("format.structure");
        assertThat(conclusion.polarity()).isEqualTo("REQUIRE");
        assertThat(longer.slot()).isEqualTo("answer.style");
        assertThat(order.slot()).isEqualTo("format.structure");
        assertThat(emoji.slot()).isEqualTo("answer.constraint");
        assertThat(emoji.polarity()).isEqualTo("FORBID");
        assertThat(MemorySlotConflict.exact(longer, List.of(peer(conclusion))).legacyStatus())
                .isEqualTo("NO_CONFLICT");
        assertThat(MemorySlotConflict.exact(emoji, List.of(peer(order))).legacyStatus())
                .isEqualTo("NO_CONFLICT");
    }

    @Test
    void sameSlotDetectsPolarityBeforeValueAndKeepsEquivalent() {
        MemorySlots.Assignment require = assignment("format.structure", "先结论后结构", "REQUIRE");
        MemorySlotConflict.Peer same = peer(require);
        MemorySlotConflict.Peer opposite = new MemorySlotConflict.Peer(
                "old", "先结论后结构", "format.structure", "先结论后结构", "FORBID", true);
        MemorySlotConflict.Peer otherValue = new MemorySlotConflict.Peer(
                "other", "先证据后结论", "format.structure", "先证据后结论", "REQUIRE", true);
        MemorySlotConflict.Peer otherNeighborhood = new MemorySlotConflict.Peer(
                "far", "先证据后结论", "format.structure", "先证据后结论", "REQUIRE", false);

        assertThat(MemorySlotConflict.exact(require, List.of(same)).legacyStatus())
                .isEqualTo("EXISTING_EQUIVALENT");
        MemorySlotConflict.Outcome polarity = MemorySlotConflict.exact(require, List.of(same, opposite));
        assertThat(polarity.conflictType()).isEqualTo("POLARITY");
        assertThat(polarity.legacyStatus()).isEqualTo("CONFLICTING_ACTIVE_MEMORY");
        assertThat(polarity.conflictingItemId()).isEqualTo("old");
        assertThat(MemorySlotConflict.exact(require, List.of(otherValue)).conflictType()).isEqualTo("SLOT_VALUE");
        assertThat(MemorySlotConflict.exact(require, List.of(otherNeighborhood)).legacyStatus())
                .isEqualTo("NO_CONFLICT");
        assertThat(MemorySlotConflict.exact(
                new MemorySlots.Assignment("unknown", "x", "REQUIRE"), List.of(opposite)).legacyStatus())
                .isEqualTo("NO_CONFLICT");
    }

    @Test
    void judgeRetriesOnceThenAsksForReview() {
        List<MemoryVectorIndex.Hit> hits = List.of(
                new MemoryVectorIndex.Hit("a", "回答使用中文", "answer.language", 0.9),
                new MemoryVectorIndex.Hit("b", "回答使用英文", "answer.language", 0.8));
        MemorySlots.Assignment incoming = assignment("answer.language", "回答使用英文", "REQUIRE");
        AtomicInteger calls = new AtomicInteger();
        ChatLlmClient client = new ChatLlmClient() {
            @Override
            public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                     Consumer<String> onToken) {
                return calls.incrementAndGet() == 1 ? "不是 JSON" : "{\"action\":\"SUPERSEDE\",\"target_id\":\"b\"}";
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };

        MemorySlotConflict.Outcome outcome = MemoryConflictJudge.judge(client, "回答使用英文", incoming, hits);

        assertThat(calls).hasValue(2);
        assertThat(outcome.conflictType()).isEqualTo("SEMANTIC");
        assertThat(outcome.action()).isEqualTo("SUPERSEDE");
        assertThat(outcome.conflictingItemId()).isEqualTo("b");
        assertThat(outcome.blocking()).isTrue();
    }

    @Test
    void judgeStopsAfterTwoInvalidOutputs() {
        ChatLlmClient client = new ChatLlmClient() {
            @Override
            public String streamChat(String systemPrompt, String userPrompt, int maximumOutputTokens,
                                     Consumer<String> onToken) {
                return "仍然不行";
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
        MemorySlotConflict.Outcome outcome = MemoryConflictJudge.judge(
                client, "回答使用英文", assignment("answer.language", "回答使用英文", "REQUIRE"),
                List.of(new MemoryVectorIndex.Hit("a", "回答使用中文", "answer.language", 0.9)));

        assertThat(outcome.conflictType()).isEqualTo("CHECK_NOT_RUN");
        assertThat(outcome.legacyStatus()).isEqualTo("CONFLICT_CHECK_NOT_RUN");
        assertThat(outcome.conflictingItemId()).isEqualTo("a");
    }

    @Test
    void missingClientLeavesTheIndexUntouched() {
        ElasticsearchMemoryVectorIndex index = new ElasticsearchMemoryVectorIndex(
                (co.elastic.clients.elasticsearch.ElasticsearchClient) null, "noteweave-memory", 4);
        assertThat(index.available()).isFalse();
        assertThat(index.upsert(new MemoryVectorIndex.Document(
                "workspace", "item", "answer.style", "语气正式", "COMMON", List.of(1f, 0f, 0f, 0f)))).isFalse();
        assertThat(index.delete("workspace", "item")).isFalse();
        assertThat(index.search("workspace", "COMMON", List.of(1f, 0f, 0f, 0f), 5).available()).isFalse();
    }

    private static MemorySlots.Assignment assignment(String slot, String value, String polarity) {
        return new MemorySlots.Assignment(slot, value, polarity);
    }

    private static MemorySlotConflict.Peer peer(MemorySlots.Assignment assignment) {
        return new MemorySlotConflict.Peer(
                "item", assignment.value(), assignment.slot(), assignment.value(), assignment.polarity(), true);
    }
}
