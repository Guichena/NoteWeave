package com.noteweave.artifact;

import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.worker.ArtifactWorkerRestClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class HttpArtifactWorkerExportClient implements ArtifactWorkerExportClient {
    private final RestClient worker;

    HttpArtifactWorkerExportClient(
            NoteWeaveProperties properties,
            @Value("${noteweave.internal.auth-token:}") String internalAuthToken,
            @Value("${noteweave.worker.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${noteweave.worker.read-timeout-seconds:30}") long readTimeoutSeconds) {
        worker = ArtifactWorkerRestClientFactory.create(properties.worker().artifactBaseUrl(),
                internalAuthToken, connectTimeoutSeconds, readTimeoutSeconds);
    }

    @Override
    public byte[] fetch(String taskId, String fileName) {
        try {
            byte[] content = worker.get()
                    .uri("/tasks/{taskId}/exports/{fileName}", taskId, fileName)
                    .retrieve().body(byte[].class);
            if (content == null || content.length == 0) {
                throw new BusinessException("ARTIFACT_EXPORT_EMPTY", "Artifact Worker 返回空 PDF");
            }
            return content;
        } catch (RestClientException ex) {
            throw new BusinessException("ARTIFACT_EXPORT_FETCH_FAILED",
                    "无法从 Artifact Worker 获取 PDF 文件", HttpStatus.BAD_GATEWAY);
        }
    }
}
