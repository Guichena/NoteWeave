package com.noteweave.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** F2-03：分层压缩与回忆的冻结边界、降级原因与有界候选。 */
class ConversationContextLayeringTest {

    private final ConversationLedgerProjector projector = new ConversationLedgerProjector();
    private final ContextCompressor compressor = new ContextCompressor();
    private final ConversationRecallPlanner recallPlanner = new ConversationRecallPlanner();

    @Test
    void recentTurnsStayRawAndEarlierTurnsFallBackToTheFrozenSummary() {
        List<ConversationLedgerEntry> entries = new java.util.ArrayList<>();
        for (int seq = 1; seq <= 8; seq++) {
            entries.add(current("m" + seq, seq,
                    seq % 2 == 1 ? "USER" : "ASSISTANT", "第" + seq + "条内容 " + "细节".repeat(10)));
        }
        var ledger = projector.project("conversation", 8, entries);
        var selection = compressor.compress(new ContextCompressor.Input(
                "conversation", 8, ledger.entries(),
                List.of(new ContextCompressor.SummaryBlock(
                        ContextItemForm.TOPIC_SUMMARY, "summary-1", 1, 4, "早期四条的冻结摘要", "READY")),
                40, 200, 100, 2, "summary-v1"));

        assertThat(selection.items())
                .anyMatch(item -> item.layer() == ContextLayer.TOPIC_SUMMARY
                        && item.text().contains("冻结摘要"));
        assertThat(selection.items())
                .anyMatch(item -> item.layer() == ContextLayer.RAW_MESSAGE
                        && item.text().contains("第8条内容"));
        assertThat(selection.items())
                .noneMatch(item -> item.layer() == ContextLayer.RAW_MESSAGE
                        && item.text().contains("第1条内容"));
        assertThat(selection.degradationReasons()).isNotEmpty();
    }

    @Test
    void deletedFailedAndResearchPendingTurnsKeepLedgerPlaceholders() {
        var ledger = projector.project("conversation", 5, List.of(
                current("m1", 1, "USER", "正常提问"),
                new ConversationLedgerEntry("m2", 2, "ASSISTANT", "", "DELETED", "", "", "", ""),
                new ConversationLedgerEntry("m3", 3, "ASSISTANT", "", "FAILED", "", "", "", ""),
                new ConversationLedgerEntry("m4", 4, "ASSISTANT", "DEEP_RESEARCH", "PENDING", "", "", "", ""),
                current("m5", 5, "USER", "继续")));

        var selection = compressor.compress(new ContextCompressor.Input(
                "conversation", 5, ledger.entries(), List.of(),
                100, 100, 100, 8, "summary-v1"));

        assertThat(selection.items())
                .extracting(ContextView.Item::layer)
                .contains(ContextLayer.MESSAGE_HANDLE);
        assertThat(selection.items())
                .extracting(ContextView.Item::text)
                .anyMatch(text -> text.contains("已删除"))
                .anyMatch(text -> text.contains("执行失败"))
                .anyMatch(text -> text.contains("研究尚未完成"));
    }

    @Test
    void invalidRecallHandleIsRejectedWithoutGuessing() {
        var result = recallPlanner.recall(new ConversationRecallPlanner.RecallRequest(
                "前面第几点怎么说的",
                List.of("handle-missing"),
                Map.of("handle-1", new ConversationRecallPlanner.HandleRange(1, 2)),
                10,
                List.of(new ConversationRecallPlanner.Candidate("m1", 1, "USER", "旧问题", 10)),
                8, 3, 0.1, 500));

        assertThat(result.hits()).isEmpty();
        assertThat(result.misses())
                .anyMatch(miss -> ConversationRecallPlanner.REASON_INVALID_HANDLE.equals(miss.reason()));
    }

    @Test
    void handleBeyondCutoffIsRejectedAndBm25StaysWithinCandidateLimit() {
        var beyond = recallPlanner.recall(new ConversationRecallPlanner.RecallRequest(
                "展开那个句柄", List.of("h-old"),
                Map.of("h-old", new ConversationRecallPlanner.HandleRange(5, 6)),
                4,
                List.of(new ConversationRecallPlanner.Candidate("m5", 5, "ASSISTANT", "越界正文", 10)),
                8, 3, 0.1, 500));
        assertThat(beyond.hits()).isEmpty();
        assertThat(beyond.misses())
                .anyMatch(miss -> ConversationRecallPlanner.REASON_BEYOND_CUTOFF.equals(miss.reason()));

        List<ConversationRecallPlanner.Candidate> candidates = new java.util.ArrayList<>();
        for (int seq = 1; seq <= 20; seq++) {
            candidates.add(new ConversationRecallPlanner.Candidate("m" + seq, seq,
                    seq % 2 == 0 ? "ASSISTANT" : "USER",
                    "第" + seq + "条 " + (seq == 19 ? "缓存一致性关键结论" : "无关内容"), 20));
        }
        var recalled = recallPlanner.recall(new ConversationRecallPlanner.RecallRequest(
                "缓存一致性关键结论是什么", List.of(), Map.of(), 20, candidates,
                5, 2, 0.01, 500));

        assertThat(recalled.candidateLimit()).isEqualTo(5);
        assertThat(recalled.hits()).isNotEmpty();
    }

    private ConversationLedgerEntry current(String id, int seq, String role, String text) {
        return new ConversationLedgerEntry(id, seq, role, "",
                ConversationLedgerEntry.STATUS_CURRENT, text, "", "", "");
    }
}
