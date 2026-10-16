package com.noteweave.infra;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionCoordinator;
import com.noteweave.source.SourceParseService;
import com.noteweave.task.TaskService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaRetrievalProjectionConsumerTest {

    @Test
    void embeddingBatchCarriesFrozenVersionAndNumber() {
        SourceRetrievalProjectionCoordinator coordinator = mock(SourceRetrievalProjectionCoordinator.class);
        KafkaTaskConsumer consumer = new KafkaTaskConsumer(mock(SourceParseService.class), mock(WikiIngestService.class),
                mock(TaskService.class), new ObjectMapper(), coordinator);
        consumer.onSourceEmbed(embedRecord("1", "\"version-1\""));
        verify(coordinator).embedStage("workspace-1", "source-1", "snapshot-1", "task-1", 1, "version-1");
    }

    @Test
    void malformedEmbeddingBatchCannotReachCoordinator() {
        SourceRetrievalProjectionCoordinator coordinator = mock(SourceRetrievalProjectionCoordinator.class);
        KafkaTaskConsumer consumer = new KafkaTaskConsumer(mock(SourceParseService.class), mock(WikiIngestService.class),
                mock(TaskService.class), new ObjectMapper(), coordinator);
        for (String number : new String[]{"1.5", "-1", "\"1\"", "null", "2147483648"}) {
            assertThatThrownBy(() -> consumer.onSourceEmbed(embedRecord(number, "\"version-1\"")))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> consumer.onSourceEmbed(embedRecord("1", "\"\"")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(coordinator);
    }

    private ConsumerRecord<String, String> embedRecord(String batch, String version) {
        return new ConsumerRecord<>("noteweave.source.embed", 0, 1L, "snapshot-1",
                """
                {"taskId":"task-1","workspaceId":"workspace-1","sourceId":"source-1",
                 "sourceSnapshotId":"snapshot-1","batchNo":%s,"batchVersion":%s}
                """.formatted(batch, version));
    }

    @Test
    void shouldDispatchProjectionPayloadToCoordinator() {
        SourceRetrievalProjectionCoordinator coordinator = mock(SourceRetrievalProjectionCoordinator.class);
        KafkaTaskConsumer consumer = new KafkaTaskConsumer(
                mock(SourceParseService.class),
                mock(WikiIngestService.class),
                mock(TaskService.class),
                new ObjectMapper(),
                coordinator
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "noteweave.retrieval.projection",
                0,
                9L,
                "snapshot-1",
                """
                {
                  "taskId":"task-1",
                  "workspaceId":"workspace-1",
                  "sourceId":"source-1",
                  "sourceSnapshotId":"snapshot-1"
                }
                """
        );

        consumer.onRetrievalProjection(record);

        verify(coordinator).projectAndFinalize(
                "workspace-1", "source-1", "snapshot-1", "task-1");
    }
}
