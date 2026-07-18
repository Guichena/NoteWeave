package com.noteweave.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

@Component
public class HttpArtifactOutboxPublisher implements ArtifactOutboxPublisher {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpArtifactOutboxPublisher(
            ObjectMapper objectMapper,
            NoteWeaveProperties properties,
            @Value("${noteweave.internal.auth-token:}") String internalAuthToken
    ) {
        this.objectMapper = objectMapper;
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.worker().artifactBaseUrl());
        if (internalAuthToken != null && !internalAuthToken.isBlank()) {
            builder.defaultHeader("X-NoteWeave-Internal-Token", internalAuthToken.trim());
        }
        this.restClient = builder.build();
    }

    @Override
    public void publish(String topic, String messageKey, String payloadJson) {
        String taskId = extractTaskId(payloadJson);
        restClient.post()
                .uri("/tasks/{taskId}/run", taskId)
                .retrieve()
                .toBodilessEntity();
    }

    private String extractTaskId(String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            String taskId = payload.path("task_id").asText().trim();
            if (taskId.isEmpty()) {
                throw new IllegalArgumentException("artifact outbox payload is missing task_id");
            }
            return taskId;
        } catch (Exception ex) {
            throw new IllegalArgumentException("artifact outbox payload is invalid", ex);
        }
    }
}
