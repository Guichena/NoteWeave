package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationResearchProjectionContextTest {

    @Test
    void reportBodyKeepsConclusionsAndDropsEvidenceKeysAndAuditForLaterTurns() {
        String report = String.join("\n",
                "# Kafka 与 Pulsar 的顺序保证",
                "",
                "Kafka 只在单个分区内有序 [evidence:abc123]。",
                "",
                "## 直接回答",
                "",
                "Pulsar 的 Key_Shared 按 key 保序 [evidence:def456][evidence:aa11]。",
                "",
                "## Citation audit",
                "",
                "### [1] Kafka docs",
                "- Evidence: `evidence:abc123`");

        String body = ConversationResearchProjectionService.contextBody(report);

        assertThat(body).startsWith("Kafka 只在单个分区内有序。")
                .contains("## 直接回答", "Pulsar 的 Key_Shared 按 key 保序。")
                .doesNotContain("evidence:", "Citation audit", "# Kafka 与 Pulsar");
    }

    @Test
    void longReportsAreCutForTheConversationContext() {
        assertThat(ConversationResearchProjectionService.contextBody("正文".repeat(4000))).hasSize(6001).endsWith("…");
        assertThat(ConversationResearchProjectionService.contextBody(null)).isEmpty();
    }
}
