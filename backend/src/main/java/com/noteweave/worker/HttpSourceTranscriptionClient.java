package com.noteweave.worker;

import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.source.SourceTranscriptionPort;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 把音视频原件以二进制流发给 Artifact Worker 的转写接口；Worker 保存文件后立即返回 202。 */
@Component
class HttpSourceTranscriptionClient implements SourceTranscriptionPort {

    private final RestClient worker;

    HttpSourceTranscriptionClient(
            NoteWeaveProperties properties,
            @Value("${noteweave.internal.artifact-auth-token:${noteweave.internal.auth-token:}}") String internalAuthToken,
            @Value("${noteweave.worker.connect-timeout-seconds:3}") long connectTimeoutSeconds,
            @Value("${noteweave.worker.transcription-submit-timeout-seconds:180}") long submitTimeoutSeconds) {
        worker = ArtifactWorkerRestClientFactory.create(properties.worker().artifactBaseUrl(),
                internalAuthToken, connectTimeoutSeconds, submitTimeoutSeconds);
    }

    @Override
    public void submit(Request request, Path mediaFile) {
        try {
            worker.post()
                    .uri(builder -> builder.path("/internal/source-transcriptions")
                            .queryParam("workspace_id", request.workspaceId())
                            .queryParam("source_id", request.sourceId())
                            .queryParam("snapshot_id", request.snapshotId())
                            .queryParam("file_name", request.fileName())
                            .queryParam("mime_type", request.mimeType())
                            .build())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new FileSystemResource(mediaFile))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            // 提交失败是暂时性的（Worker 未就绪等），由解析阶段的 Kafka 重试处理
            throw new BusinessException("SOURCE_TRANSCRIPTION_SUBMIT_FAILED",
                    "无法把音视频提交给转写服务", HttpStatus.BAD_GATEWAY);
        }
    }
}
