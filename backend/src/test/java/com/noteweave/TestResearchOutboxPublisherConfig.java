package com.noteweave;

import com.noteweave.worker.ResearchOutboxPublisher;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
class TestResearchOutboxPublisherConfig {

    @Bean
    @Primary
    RecordingResearchOutboxPublisher recordingResearchOutboxPublisher() {
        return new RecordingResearchOutboxPublisher();
    }

    static class RecordingResearchOutboxPublisher implements ResearchOutboxPublisher {

        private final List<PublishedMessage> messages = new ArrayList<>();

        @Override
        public void publish(String topic, String messageKey, String payloadJson) {
            messages.add(new PublishedMessage(topic, messageKey, payloadJson));
        }

        List<PublishedMessage> messages() {
            return messages;
        }

        void reset() {
            messages.clear();
        }
    }

    record PublishedMessage(String topic, String messageKey, String payloadJson) {
    }
}
