package com.noteweave.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

@Component
public class HttpArtifactOutboxPublisher implements ArtifactOutboxPublisher {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpArtifactOutboxPublisher(
            ObjectMapper objectMapper,
            NoteWeaveProperties properties,
            @Value("${noteweave.internal.artifact-auth-token:${noteweave.internal.auth-token:}}") String internalAuthToken,
            @Value("${noteweave.worker.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${noteweave.worker.read-timeout-seconds:30}") long readTimeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.restClient = ArtifactWorkerRestClientFactory.create(
                properties.worker().artifactBaseUrl(),
                internalAuthToken,
                connectTimeoutSeconds,
                readTimeoutSeconds
        );
    }

    public HttpArtifactOutboxPublisher(
            ObjectMapper objectMapper,
            NoteWeaveProperties properties,
            String internalAuthToken
    ) {
        this(objectMapper, properties, internalAuthToken, 3, 30);
    }

    @Override
    public void publish(String topic, String messageKey, String payloadJson, String deliveryToken) {
        String taskId = extractTaskId(payloadJson);
        restClient.post()
                .uri("/tasks/{taskId}/run", taskId)
                .header(DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER, deliveryToken)
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
