package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.FinalizationOutcome;
import com.noteweave.infra.outbox.OutboxFinalizationRepository;
import com.noteweave.memory.MemoryProcessingReceiptService;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionFinalizer;
import com.noteweave.source.SourceParseFailureFinalizer;
import com.noteweave.source.SourceTopicNamesFixture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * MEM-01：记忆抽取消息重试耗尽进入 DLT 后，回执必须推进到 RETRY_WAIT/TERMINAL_FAILURE，
 * 任务不能停在 RUNNING。旧实现只登记了 MEMORY_EXTRACTION 补偿类型却没有对应分支，
 * 回执原样不动、补偿行还被写成 IGNORED_STALE。
 */
class KafkaMemoryExtractionDeadLetterRecoveryTest {

    private static final String MEMORY_TOPIC = "noteweave.memory.extraction";
    private static final String RECOVERY_ID = MEMORY_TOPIC + ":0:3:noteweave";
    private static final String PAYLOAD = """
            {"task_id":"task-mem-1","workspace_id":"workspace-1","message_id":"message-1"}
            """;

    private final MemoryProcessingReceiptService receipts = mock(MemoryProcessingReceiptService.class);
    private final OutboxFinalizationRepository finalizations = mock(OutboxFinalizationRepository.class);

    @Test
    void deadLetteredExtractionAdvancesTheReceiptAndClosesTheCompensation() {
        when(receipts.finalizeDeadLettered("workspace-1", "message-1", "MEMORY_EXTRACTION_FAILED"))
                .thenReturn(FinalizationOutcome.APPLIED);

        recovery(true).recover(record(PAYLOAD), new IllegalStateException("handler failed"));

        verify(receipts).finalizeDeadLettered("workspace-1", "message-1", "MEMORY_EXTRACTION_FAILED");
        verify(finalizations).insertPending(
                eq(OutboxFinalizationRepository.STORE_KAFKA_DLT), eq(RECOVERY_ID), eq(1),
                eq("MEMORY_EXTRACTION"), eq("workspace-1"), anyMap(), eq(5));
        verify(finalizations).completePending(
                OutboxFinalizationRepository.STORE_KAFKA_DLT, RECOVERY_ID, 1,
                OutboxFinalizationRepository.STATUS_APPLIED, null);
    }

    @Test
    void businessErrorCodeFromTheConsumerIsKeptOnTheReceipt() {
        when(receipts.finalizeDeadLettered("workspace-1", "message-1", "MEMORY_MODEL_UNAVAILABLE"))
                .thenReturn(FinalizationOutcome.APPLIED);

        recovery(true).recover(record(PAYLOAD),
                new IllegalStateException("handler failed", new BusinessException(
                        "MEMORY_MODEL_UNAVAILABLE", "记忆抽取模型不可用")));

        // 不能把检索投影的默认错误码写到记忆回执上
        verify(receipts).finalizeDeadLettered("workspace-1", "message-1", "MEMORY_MODEL_UNAVAILABLE");
    }

    @Test
    void withoutTheReceiptServiceTheTopicIsNotRegisteredAtAll() {
        recovery(false).recover(record(PAYLOAD), new IllegalStateException("handler failed"));

        verifyNoInteractions(finalizations);
        verifyNoInteractions(receipts);
    }

    @Test
    void missingIdentityIsRecordedAsStaleInsteadOfHanging() {
        recovery(true).recover(record("{}"), new IllegalStateException("handler failed"));

        verifyNoInteractions(receipts);
        verify(finalizations).completePending(
                OutboxFinalizationRepository.STORE_KAFKA_DLT, RECOVERY_ID, 1,
                OutboxFinalizationRepository.STATUS_IGNORED_STALE, null);
    }

    @Test
    void failureWhileClosingTheReceiptIsRetriedInsteadOfThrown() {
        doThrow(new IllegalStateException("db down"))
                .when(receipts).finalizeDeadLettered("workspace-1", "message-1", "MEMORY_EXTRACTION_FAILED");

        assertThatCode(() -> recovery(true).recover(record(PAYLOAD), new IllegalStateException("handler failed")))
                .doesNotThrowAnyException();

        verify(finalizations).retryPending(
                eq(OutboxFinalizationRepository.STORE_KAFKA_DLT), eq(RECOVERY_ID), eq(1),
                anyString(), anyString(), anyLong());
        verify(finalizations, never()).completePending(
                anyString(), anyString(), anyInt(), anyString(), any());
    }

    private KafkaDeadLetterTaskRecovery recovery(boolean withReceiptService) {
        KafkaDeadLetterTaskRecovery recovery = new KafkaDeadLetterTaskRecovery(
                new ObjectMapper(),
                mock(SourceRetrievalProjectionFinalizer.class),
                mock(SourceParseFailureFinalizer.class),
                SourceTopicNamesFixture.defaults(),
                "noteweave",
                MEMORY_TOPIC);
        if (withReceiptService) {
            recovery.setMemoryReceipts(receipts);
        }
        recovery.setFinalizations(finalizations);
        return recovery;
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(MEMORY_TOPIC, 0, 3L, "message-1", value);
    }
}
