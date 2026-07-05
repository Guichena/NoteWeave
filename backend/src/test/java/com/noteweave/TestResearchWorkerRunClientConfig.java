package com.noteweave;

import com.noteweave.worker.ResearchWorkerRunClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
class TestResearchWorkerRunClientConfig {

    @Bean
    @Primary
    RecordingResearchWorkerRunClient recordingResearchWorkerRunClient() {
        return new RecordingResearchWorkerRunClient();
    }

    static class RecordingResearchWorkerRunClient implements ResearchWorkerRunClient {

        private final List<String> taskIds = new ArrayList<>();

        @Override
        public void runTask(String taskId) {
            taskIds.add(taskId);
        }

        List<String> taskIds() {
            return taskIds;
        }

        void reset() {
            taskIds.clear();
        }
    }
}
