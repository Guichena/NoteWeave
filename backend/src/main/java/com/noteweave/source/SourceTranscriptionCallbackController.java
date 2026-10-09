package com.noteweave.source;

import com.noteweave.common.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Artifact Worker 完成音视频转写后的回调，使用 Artifact Worker 的内部令牌鉴权。 */
@RestController
@RequestMapping("/internal/worker/source-transcriptions")
public class SourceTranscriptionCallbackController {

    private final SourceTranscriptionService transcriptionService;

    public SourceTranscriptionCallbackController(SourceTranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @PostMapping("/{snapshotId}")
    ApiResponse<Map<String, Object>> complete(
            @PathVariable String snapshotId,
            @RequestBody SourceTranscriptionService.TranscriptionCallback callback
    ) {
        boolean accepted = transcriptionService.handle(snapshotId, callback);
        return ApiResponse.success(Map.of("snapshot_id", snapshotId, "accepted", accepted));
    }
}
