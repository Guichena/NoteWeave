package com.noteweave.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.common.Json;
import com.noteweave.infra.outbox.DurableOutboxDispatcher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

@Component
public class HttpArtifactWorkerControlClient implements ArtifactWorkerControlClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpArtifactWorkerControlClient(
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

    public HttpArtifactWorkerControlClient(ObjectMapper objectMapper, NoteWeaveProperties properties) {
        this(objectMapper, properties, "", 3, 30);
    }

    public HttpArtifactWorkerControlClient(
            ObjectMapper objectMapper,
            NoteWeaveProperties properties,
            String internalAuthToken
    ) {
        // Compatibility constructor retained for direct tests and non-Spring callers.
        this(objectMapper, properties, internalAuthToken, 3, 30);
    }

    @Override
    public ArtifactWorkerExecutionResponse resumeTask(String taskId, ArtifactWorkerResumeRequest request) {
        String responseBody = restClient.post()
                .uri("/tasks/{taskId}/resume", taskId)
                .header(DurableOutboxDispatcher.DELIVERY_TOKEN_HEADER, request.deliveryToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Json.write(objectMapper, request))
                .retrieve()
                .body(String.class);
        return parseApiData(responseBody, ArtifactWorkerExecutionResponse.class);
    }

    @Override
    public ArtifactAcquisitionAckResponse acknowledgeAcquisition(ArtifactAcquisitionAckRequest request) {
        String responseBody = restClient.post()
                .uri("/callbacks/acquisition/ack")
                .header("X-NoteWeave-Defer-Resume", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Json.write(objectMapper, request))
                .retrieve()
                .body(String.class);
        return parseApiData(responseBody, ArtifactAcquisitionAckResponse.class);
    }

    private <T> T parseApiData(String responseBody, Class<T> targetType) {
        try {
            JsonNode root = objectMapper.readTree(responseBody == null ? "{}" : responseBody);
            JsonNode data = root;
            if (root.has("success")) {
                if (!root.path("success").asBoolean(false)) {
                    throw new IllegalStateException(
                            "artifact worker control request failed: "
                                    + root.path("code").asText("ERROR")
                                    + " "
                                    + root.path("message").asText("")
                    );
                }
                data = root.path("data");
            }
            if (data.isMissingNode() || data.isNull()) {
                throw new IllegalStateException("artifact worker control response is missing data");
            }
            return objectMapper.treeToValue(data, targetType);
        } catch (Exception ex) {
            throw new IllegalStateException("artifact worker control response is invalid", ex);
        }
    }
}
