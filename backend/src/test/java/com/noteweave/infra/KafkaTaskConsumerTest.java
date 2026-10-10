package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.conversation.PromoteSegmentSummaryRequest;
import com.noteweave.conversation.SegmentSummaryPromotionService;
import com.noteweave.source.SourceParseService;
import com.noteweave.source.SourceParseService.SourceParseAssessment;
import com.noteweave.source.SourceParseService.SourceParseDisposition;
import com.noteweave.task.TaskService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KafkaTaskConsumerTest {

    private final SourceParseService sourceParseService = mock(SourceParseService.class);
    private final WikiIngestService wikiIngestService = mock(WikiIngestService.class);
    private final TaskService taskService = mock(TaskService.class);
    private final KafkaTaskConsumer consumer = new KafkaTaskConsumer(
            sourceParseService, wikiIngestService, taskService, new ObjectMapper());

    @Test
    void malformedMessageShouldEscapeHandlerSoOffsetIsNotAcknowledgedAsSuccess() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "noteweave.wiki.ingest", 0, 7L, "task-1", "not-json"
        );

        assertThatThrownBy(() -> consumer.onWikiIngest(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka wiki.ingest handler failed");
    }

    @Test
    void processableSourceParseShouldStartAndExecute() {
        when(sourceParseService.assessProcessability("workspace-1", "source-1", "snapshot-1"))
                .thenReturn(assessment(SourceParseDisposition.PROCESSABLE, "PROCESSING", "PENDING"));
        when(sourceParseService.parseAndIndexAsyncIfProcessable(
                "workspace-1", "source-1", "snapshot-1")).thenReturn(true);

        consumer.onSourceParse(sourceParseRecord("task-1"));

        verify(taskService).startTask("task-1");
        verify(sourceParseService).parseAndIndexAsyncIfProcessable(
                "workspace-1", "source-1", "snapshot-1");
    }

    @Test
    void deletedSourceShouldCancelPendingParseTask() {
        when(sourceParseService.assessProcessability("workspace-1", "source-1", "snapshot-1"))
                .thenReturn(assessment(SourceParseDisposition.TARGET_DELETED, "DELETED", "DELETED"));
        when(taskService.getTaskRef("task-1")).thenReturn(taskRef("PENDING"));

        consumer.onSourceParse(sourceParseRecord("task-1"));

        verify(taskService).cancelTask(
                "task-1",
                "SOURCE_PARSE_TARGET_DELETED",
                "资料或快照已删除，取消解析：source_status=DELETED, snapshot_parse_status=DELETED",
                "source-1"
        );
        verify(taskService, never()).startTask("task-1");
    }

    @Test
    void duplicateMessageForCompletedTaskShouldAckWithoutAnotherTransition() {
        when(sourceParseService.assessProcessability("workspace-1", "source-1", "snapshot-1"))
                .thenReturn(assessment(SourceParseDisposition.ALREADY_HANDLED, "READY", "PARSED"));
        when(taskService.getTaskRef("task-1")).thenReturn(taskRef("COMPLETED"));

        consumer.onSourceParse(sourceParseRecord("task-1"));

        verify(taskService, never()).startTask("task-1");
        verify(taskService, never()).cancelTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(taskService, never()).failTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void missingParseTargetShouldFailTaskWithoutRetry() {
        when(sourceParseService.assessProcessability("workspace-1", "source-1", "snapshot-1"))
                .thenReturn(assessment(SourceParseDisposition.TARGET_MISSING, "MISSING", "MISSING"));
        when(taskService.getTaskRef("task-1")).thenReturn(taskRef("PENDING"));

        consumer.onSourceParse(sourceParseRecord("task-1"));

        verify(taskService).failTask(
                "task-1",
                "SOURCE_PARSE_TARGET_MISSING",
                "资料解析目标不存在或与消息不匹配：source_status=MISSING, snapshot_parse_status=MISSING",
                "SOURCE_PARSE_TARGET_MISSING",
                false
        );
    }

    @Test
    void concurrentStateChangeAfterStartShouldCancelRunningTask() {
        when(sourceParseService.assessProcessability("workspace-1", "source-1", "snapshot-1"))
                .thenReturn(
                        assessment(SourceParseDisposition.PROCESSABLE, "PROCESSING", "PENDING"),
                        assessment(SourceParseDisposition.ALREADY_HANDLED, "READY", "PARSED")
                );
        when(sourceParseService.parseAndIndexAsyncIfProcessable(
                "workspace-1", "source-1", "snapshot-1")).thenReturn(false);
        when(taskService.getTaskRef("task-1")).thenReturn(taskRef("RUNNING"));

        consumer.onSourceParse(sourceParseRecord("task-1"));

        verify(taskService).startTask("task-1");
        verify(taskService).cancelTask(
                "task-1",
                "SOURCE_PARSE_DUPLICATE",
                "资料快照已处理，确认重复消息并取消冗余任务：source_status=READY, snapshot_parse_status=PARSED",
                "source-1"
        );
    }

    @Test
    void missingTaskIdShouldRejectMessageInsteadOfAcknowledgingIt() {
        assertThatThrownBy(() -> consumer.onSourceParse(sourceParseRecord(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka source.parse handler failed");
    }

    @Test
    void conversationSummaryMessageShouldPromoteTheFrozenSourceMessages() {
        SegmentSummaryPromotionService promotionService = mock(SegmentSummaryPromotionService.class);
        KafkaTaskConsumer summaryConsumer = new KafkaTaskConsumer(
                sourceParseService, wikiIngestService, taskService, new ObjectMapper(), null, promotionService);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "noteweave.conversation.summary", 0, 9L, "revision-1", """
                {
                  "segment_id":"segment-1",
                  "summary_revision_id":"revision-1",
                  "source_messages":[
                    {"role":"USER","content":"First question"},
                    {"role":"ASSISTANT","content":"Grounded answer"}
                  ]
                }
                """);

        summaryConsumer.onConversationSummary(record);

        ArgumentCaptor<PromoteSegmentSummaryRequest> request =
                ArgumentCaptor.forClass(PromoteSegmentSummaryRequest.class);
        verify(promotionService).promote(
                org.mockito.ArgumentMatchers.eq("segment-1"),
                org.mockito.ArgumentMatchers.eq("revision-1"),
                request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().summaryText())
                .isEqualTo("user: First question\nassistant: Grounded answer");
        org.assertj.core.api.Assertions.assertThat(request.getValue().contentHash())
                .matches("[0-9a-f]{64}");
    }

    @Test
    void conversationSummaryWithBaseSummaryOnlyAppendsTheNewTurns() {
        SegmentSummaryPromotionService promotionService = mock(SegmentSummaryPromotionService.class);
        KafkaTaskConsumer summaryConsumer = new KafkaTaskConsumer(
                sourceParseService, wikiIngestService, taskService, new ObjectMapper(), null, promotionService);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "noteweave.conversation.summary", 0, 11L, "revision-2", """
                {"segment_id":"segment-2","summary_revision_id":"revision-2",
                 "base_summary_text":"earlier summary",
                 "source_messages":[{"role":"USER","content":"New question"}]}
                """);

        summaryConsumer.onConversationSummary(record);

        // 未配置大模型时按抽取式摘要处理：旧摘要加上新增消息
        verify(promotionService).promote(org.mockito.ArgumentMatchers.eq("segment-2"),
                org.mockito.ArgumentMatchers.eq("revision-2"),
                org.mockito.ArgumentMatchers.argThat(request ->
                        "earlier summary\nuser: New question".equals(request.summaryText())
                                && "EXTRACTIVE".equals(request.summaryMethod())));
    }

    @Test
    void topicSummaryV2MessageRoutesToV2Promotion() {
        com.noteweave.conversation.ConversationTopicSummaryV2Service v2 =
                mock(com.noteweave.conversation.ConversationTopicSummaryV2Service.class);
        KafkaTaskConsumer summaryConsumer = new KafkaTaskConsumer(sourceParseService,
                wikiIngestService, taskService, new ObjectMapper(), null, null, v2);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "noteweave.conversation.summary", 0, 10L, "revision-v2", """
                {"summary_projection_version":2,"segment_id":"segment-v2",
                 "summary_revision_id":"revision-v2",
                 "source_messages":[{"role":"USER","content":"Frozen topic text"}]}
                """);

        summaryConsumer.onConversationSummary(record);

        verify(v2).promote(org.mockito.ArgumentMatchers.eq("segment-v2"),
                org.mockito.ArgumentMatchers.eq("revision-v2"),
                org.mockito.ArgumentMatchers.argThat(request ->
                        "user: Frozen topic text".equals(request.summaryText())
                                && request.contentHash().matches("[0-9a-f]{64}")));
    }

    private ConsumerRecord<String, String> sourceParseRecord(String taskId) {
        String taskJson = taskId == null ? "null" : "\"" + taskId + "\"";
        return new ConsumerRecord<>(
                "noteweave.source.parse",
                0,
                8L,
                "source-1",
                "{\"taskId\":" + taskJson
                        + ",\"workspaceId\":\"workspace-1\",\"sourceId\":\"source-1\",\"snapshotId\":\"snapshot-1\"}"
        );
    }

    private SourceParseAssessment assessment(
            SourceParseDisposition disposition,
            String sourceStatus,
            String snapshotStatus
    ) {
        return new SourceParseAssessment(disposition, sourceStatus, snapshotStatus);
    }

    private TaskService.TaskRef taskRef(String status) {
        return new TaskService.TaskRef(
                "task-1", "workspace-1", "SOURCE_PARSE", status, "SOURCE", "source-1");
    }
}
