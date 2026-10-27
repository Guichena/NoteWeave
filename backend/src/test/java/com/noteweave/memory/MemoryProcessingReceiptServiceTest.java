package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.common.FinalizationOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Memory 处理回执阶段机测试（B-05 验收）：
 * 空候选是有效的抽取完成；反馈与抽取重试分开计数；
 * 达到上限 TERMINAL_FAILURE；无回执的消费死信按过期跳过。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemoryProcessingReceiptServiceTest {
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MemoryProcessingReceiptService receipts;

    private String uniqueMessage() {
        return "msg-" + System.nanoTime();
    }

    @Test
    void markExtractDoneAdvancesTheStageOnlyOnce() {
        String messageId = uniqueMessage();
        receipts.ensurePending("ws-1", messageId);

        // MEM-03：第二个投递推进同一条回执时必须是 0 行，调用方据此不再重复安排反馈阶段
        assertThat(receipts.markExtractDone("ws-1", messageId, "LLM", List.of())).isTrue();
        assertThat(receipts.markExtractDone("ws-1", messageId, "LLM", List.of())).isFalse();
    }

    @Test
    void ensurePendingIsIdempotentAndExtractStageStartsFirst() {
        String messageId = uniqueMessage();
        var first = receipts.ensurePending("ws-1", messageId);
        assertThat(first.status()).isEqualTo(MemoryProcessingReceiptService.STATUS_PENDING);
        assertThat(first.stage()).isEqualTo(MemoryProcessingReceiptService.STAGE_EXTRACT);
        var second = receipts.ensurePending("ws-1", messageId);
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void emptyCandidateExtractionStillCountsAsDoneAndMovesToFeedback() {
        String messageId = uniqueMessage();
        receipts.ensurePending("ws-1", messageId);
        // 空候选也是有效的抽取完成，不能用已有 signal 数量判断完成
        receipts.markExtractDone("ws-1", messageId, MemoryConversationExtractionService.METHOD_LLM, List.of());
        var receipt = receipts.load("ws-1", messageId).get(0);
        assertThat(receipt.stage()).isEqualTo(MemoryProcessingReceiptService.STAGE_FEEDBACK);
        assertThat(receipt.status()).isEqualTo(MemoryProcessingReceiptService.STATUS_PENDING);
        // 反馈不适用：显式完成
        receipts.markFeedbackNotApplicable("ws-1", messageId);
        receipt = receipts.load("ws-1", messageId).get(0);
        assertThat(receipt.status()).isEqualTo(MemoryProcessingReceiptService.STATUS_COMPLETED);
        assertThat(receipt.completed()).isTrue();
    }

    @Test
    void feedbackRetryCapIsIndependentOfExtractAttempts() {
        String messageId = uniqueMessage();
        receipts.ensurePending("ws-1", messageId);
        // 抽取阶段两次重试
        receipts.scheduleRetry("ws-1", messageId, MemoryProcessingReceiptService.STAGE_EXTRACT, "LLM_DOWN");
        receipts.scheduleRetry("ws-1", messageId, MemoryProcessingReceiptService.STAGE_EXTRACT, "LLM_DOWN");
        var receipt = receipts.load("ws-1", messageId).get(0);
        assertThat(receipt.status()).isEqualTo(MemoryProcessingReceiptService.STATUS_RETRY_WAIT);
        assertThat(receipt.extractAttempts()).isEqualTo(2);
        // 转入反馈阶段后，反馈重试从自己的计数开始，不受抽取计数影响
        receipts.markExtractDone("ws-1", messageId, "LLM", List.of());
        receipts.scheduleRetry("ws-1", messageId, MemoryProcessingReceiptService.STAGE_FEEDBACK, "JUDGE_DOWN");
        receipt = receipts.load("ws-1", messageId).get(0);
        assertThat(receipt.status()).isEqualTo(MemoryProcessingReceiptService.STATUS_RETRY_WAIT);
        assertThat(receipt.feedbackAttempts()).isEqualTo(1);
        assertThat(receipt.extractAttempts()).isEqualTo(2);
        // 反馈达到上限：TERMINAL_FAILURE(stage=FEEDBACK)，抽取已完成的事实保留
        receipts.scheduleRetry("ws-1", messageId, MemoryProcessingReceiptService.STAGE_FEEDBACK, "JUDGE_DOWN");
        receipts.scheduleRetry("ws-1", messageId, MemoryProcessingReceiptService.STAGE_FEEDBACK, "JUDGE_DOWN");
        receipt = receipts.load("ws-1", messageId).get(0);
        assertThat(receipt.status()).isEqualTo(MemoryProcessingReceiptService.STATUS_TERMINAL_FAILURE);
        assertThat(receipt.stage()).isEqualTo(MemoryProcessingReceiptService.STAGE_FEEDBACK);
    }

    @Test
    void deadLetteredMessageWithoutReceiptIsIgnoredAsStale() {
        assertThat(receipts.finalizeDeadLettered("ws-1", "unknown-" + System.nanoTime(), "MEMORY_EXTRACTION_FAILED"))
                .isEqualTo(FinalizationOutcome.IGNORED_STALE);
    }

    @Test
    void deadLetteredMessageWithPendingReceiptSchedulesBoundedRetry() {
        String messageId = uniqueMessage();
        receipts.ensurePending("ws-1", messageId);
        assertThat(receipts.finalizeDeadLettered("ws-1", messageId, "MEMORY_EXTRACTION_FAILED"))
                .isEqualTo(FinalizationOutcome.APPLIED);
        var receipt = receipts.load("ws-1", messageId).get(0);
        assertThat(receipt.status()).isEqualTo(MemoryProcessingReceiptService.STATUS_RETRY_WAIT);
        assertThat(jdbcTemplate.queryForObject(
                "select last_error_code from memory_processing_receipt where message_id = ?",
                String.class, messageId)).isEqualTo("MEMORY_EXTRACTION_FAILED");
        // 已完成的回执再次死信：幂等跳过
        receipts.markCompleted("ws-1", messageId);
        assertThat(receipts.finalizeDeadLettered("ws-1", messageId, "MEMORY_EXTRACTION_FAILED"))
                .isEqualTo(FinalizationOutcome.APPLIED);
    }
}
