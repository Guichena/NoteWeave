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
            List<String> warnings,
            @JsonProperty("page_sources") List<PageSource> pageSources
    ) {
        public ParsedDocument {
            elements = elements == null ? List.of() : List.copyOf(elements);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            pageSources = pageSources == null ? List.of() : List.copyOf(pageSources);
        }

        public ParsedDocument(int schemaVersion, String workspaceId, String sourceId, String snapshotId,
                              String sha256, String mimeType, String markdown, int pageCount,
                              List<DocumentElement> elements, String parser, boolean truncated, List<String> warnings) {
            this(schemaVersion, workspaceId, sourceId, snapshotId, sha256, mimeType, markdown, pageCount,
                    elements, parser, truncated, warnings, List.of());
        }
    }

    /** 只记录实际检查过的页面，缺失的页码或诊断不得由客户端补造。 */
    record PageSource(int page,
                      @JsonProperty("text_origin") DocumentElement.TextOrigin textOrigin,
                      @JsonProperty("ocr_confidence") Double ocrConfidence,
                      @JsonProperty("native_char_count") Integer nativeCharCount,
                      @JsonProperty("has_images") Boolean hasImages,
                      @JsonProperty("ocr_attempted") boolean ocrAttempted) {
        public PageSource {
            if (page < 1 || (nativeCharCount != null && nativeCharCount < 0)) {
                throw new IllegalArgumentException("页面来源的页码或实际文字数量无效");
            }
            DocumentElement.validateTextSource(textOrigin, ocrConfidence);
        }
    }
}
