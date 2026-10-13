package com.noteweave.source;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.file.Path;
import java.util.List;

/** 文档结构解析边界，原件从文件流读取，响应必须与请求身份和摘要一致。 */
public interface SourceDocumentParserPort {
    ParsedDocument parse(Request request, Path original);

    record Request(String workspaceId, String sourceId, String snapshotId, String fileName,
                   String mimeType, String sha256, int maxChars) {}

    record ParsedDocument(
            @JsonProperty("schema_version") int schemaVersion,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("source_id") String sourceId,
            @JsonProperty("snapshot_id") String snapshotId,
            String sha256,
            @JsonProperty("mime_type") String mimeType,
            String markdown,
            @JsonProperty("page_count") int pageCount,
            List<DocumentElement> elements,
            String parser,
            boolean truncated,
            List<String> warnings
    ) {}
}
