package com.noteweave.source;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.worker.ArtifactWorkerRestClientFactory;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 链接预览和解析提交经 Worker 调用媒体 MCP，Java 不重复实现 MCP 协议。 */
@Component
public class SourceLinkWorkerClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FAILURE_DETAIL_CHARS = 300;
    private final RestClient worker;

    public SourceLinkWorkerClient(NoteWeaveProperties properties,
            @Value("${noteweave.internal.artifact-auth-token:${noteweave.internal.auth-token:}}") String token,
            @Value("${noteweave.worker.connect-timeout-seconds:3}") long connectSeconds,
            @Value("${noteweave.worker.transcription-submit-timeout-seconds:180}") long readSeconds) {
        worker = ArtifactWorkerRestClientFactory.create(properties.worker().artifactBaseUrl(), token, connectSeconds, readSeconds);
    }

    public VideoInfo preview(String url) {
        try {
            VideoInfo info = worker.post().uri("/internal/source-video-info").body(Map.of("source_url", url))
                    .retrieve().body(VideoInfo.class);
            if (info == null || info.parts() == null || info.parts().isEmpty() || info.title() == null) {
                throw new BusinessException("SOURCE_LINK_PREVIEW_INVALID", "视频预览返回内容无效", HttpStatus.BAD_GATEWAY);
            }
            return info;
        } catch (RestClientResponseException ex) {
            // Worker 已把上游真实原因（风控、需要 Cookie 或代理等）放在 detail 里，直接交给用户比通用文案有用
            throw new BusinessException("SOURCE_LINK_PREVIEW_FAILED", previewFailureMessage(ex.getResponseBodyAsString()),
                    HttpStatus.BAD_GATEWAY);
        } catch (RestClientException ex) {
            throw new BusinessException("SOURCE_LINK_PREVIEW_FAILED", "无法读取 B 站视频信息，请检查链接后重试", HttpStatus.BAD_GATEWAY);
        }
    }

    static String previewFailureMessage(String responseBody) {
        String fallback = "无法读取 B 站视频信息，请检查链接后重试";
        if (responseBody == null || responseBody.isBlank()) return fallback;
        String detail;
        try {
            JsonNode node = JSON.readTree(responseBody).path("detail");
            detail = node.isMissingNode() || node.isNull() ? "" : node.asText("");
        } catch (Exception unparsable) {
            detail = responseBody.strip();
        }
        detail = detail.replaceAll("\\s+", " ").strip();
        if (detail.isEmpty()) return fallback;
        String clipped = detail.length() > MAX_FAILURE_DETAIL_CHARS
                ? detail.substring(0, MAX_FAILURE_DETAIL_CHARS) + "…"
                : detail;
        return "无法读取 B 站视频信息：" + clipped;
    }

    void submit(SourceTranscriptionPort.Request request, String url, List<Integer> parts, String title) {
        try {
            worker.post().uri("/internal/source-video-parses").body(Map.of(
                    "workspace_id", request.workspaceId(), "source_id", request.sourceId(), "snapshot_id", request.snapshotId(),
                    "task_id", request.taskId(), "source_url", url, "parts", parts, "title", title))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            throw new BusinessException("SOURCE_LINK_SUBMIT_FAILED", "视频解析暂未接纳，稍后自动重试", HttpStatus.BAD_GATEWAY);
        }
    }

    public record VideoInfo(String title, @JsonProperty("source_url") String sourceUrl,
                            @JsonProperty("duration_ms") long durationMs, List<Part> parts) { }
    public record Part(int part, String title, @JsonProperty("duration_ms") long durationMs) { }
}
