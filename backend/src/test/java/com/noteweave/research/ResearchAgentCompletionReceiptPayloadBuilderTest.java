package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResearchAgentCompletionReceiptPayloadBuilderTest {

    private final ResearchAgentCompletionReceiptPayloadBuilder builder =
            new ResearchAgentCompletionReceiptPayloadBuilder();

    @Test
    void receiptProjectionShouldPreserveContractAndRespectDigestBoundary() {
        ResearchAgentCompletionReceipt receipt = new ResearchAgentCompletionReceipt(
                "research-agent-completion-receipt.v1",
                "completion-1",
                "execution-1",
                "task-1",
                "sha256:completion",
                "sha256:receipt",
                false,
                "COMMITTED",
                2,
                3,
                List.of(new ResearchAgentCompletionReceipt.MergeReceipt(
                        "cell-1", 1, 2, "ACCEPTED", "SUPPORTED")),
                List.of(new ResearchAgentCompletionReceipt.MergeReceipt(
                        "cell-2", 3, 3, "REJECTED", "STALE")),
                new ResearchAgentCompletionReceipt.BudgetReceipt(
                        "SETTLED",
                        Map.of("input", 10L),
                        Map.of("input", 7L),
                        Map.of("input", 3L))
        );

        Map<String, Object> unsigned = builder.build(receipt, false);
        assertThat(unsigned)
                .containsEntry("completion_id", "completion-1")
                .containsEntry("candidate_count", 3)
                .doesNotContainKey("receipt_digest");
        assertThat(((List<?>) unsigned.get("accepted_merges"))).hasSize(1);
        assertThat(builder.build(receipt, true)).containsEntry("receipt_digest", "sha256:receipt");
    }
}
