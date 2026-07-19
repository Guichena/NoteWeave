package com.noteweave.infra;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionCoordinator;
import com.noteweave.source.SourceParseService;
import com.noteweave.task.TaskService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaRetrievalProjectionConsumerTest {

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
