package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.knowledge.WikiIngestService;
import com.noteweave.source.SourceParseService;
import com.noteweave.task.TaskService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaTaskConsumerTest {

    @Test
    void malformedMessageShouldEscapeHandlerSoOffsetIsNotAcknowledgedAsSuccess() {
        KafkaTaskConsumer consumer = new KafkaTaskConsumer(
                mock(SourceParseService.class),
                mock(WikiIngestService.class),
                mock(TaskService.class),
                new ObjectMapper()
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "noteweave.wiki.ingest", 0, 7L, "task-1", "not-json"
        );

        assertThatThrownBy(() -> consumer.onWikiIngest(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka wiki.ingest handler failed");
    }
}
