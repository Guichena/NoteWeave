package com.noteweave.source;

import java.nio.file.Path;

/**
 * 把音视频资料交给 Artifact Worker 转写。提交后立即返回，Worker 通过 MCP 转写工具生成文字稿，
 * 完成或失败时回调 {@code /internal/worker/source-transcriptions/{snapshotId}}。
 */
public interface SourceTranscriptionPort {

    void submit(Request request, Path mediaFile);

    record Request(String workspaceId, String sourceId, String snapshotId, String fileName, String mimeType) {
    }
}
