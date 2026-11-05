package com.noteweave.artifact;

public interface ArtifactWorkerExportClient {
    byte[] fetch(String taskId, String fileName);

    /** 让 Worker 把产物 Markdown 正文排成 PDF；不支持时抛出业务异常。 */
    default byte[] renderMarkdownPdf(String title, String markdown) {
        throw new com.noteweave.common.BusinessException("ARTIFACT_EXPORT_NOT_READY", "当前环境不支持生成 PDF",
                org.springframework.http.HttpStatus.CONFLICT);
    }
}
