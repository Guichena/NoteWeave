package com.noteweave.upload;

import com.noteweave.common.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class UploadSecurityPolicy {

    static final long MAX_FILE_SIZE = 128L * 1024 * 1024;
    static final int MAX_CHUNK_SIZE = 8 * 1024 * 1024;
    static final int MAX_CHUNKS = 4096;

    private static final Map<String, String> EXTENSION_MIME = Map.ofEntries(
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("txt", "text/plain"),
            Map.entry("json", "application/json"),
            Map.entry("csv", "text/csv"),
            Map.entry("pdf", "application/pdf")
    );

    public void validateMetadata(CreateUploadRequest request) {
        String fileName = request.fileName() == null ? "" : request.fileName().trim();
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")
                || fileName.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new BusinessException("UPLOAD_FILE_NAME_INVALID", "文件名包含非法路径或控制字符");
        }
        if (request.fileSize() > MAX_FILE_SIZE) {
            throw new BusinessException("UPLOAD_FILE_TOO_LARGE", "单文件最大允许 128MB");
        }
        if (request.chunkSize() <= 0 || request.chunkSize() > MAX_CHUNK_SIZE) {
            throw new BusinessException("UPLOAD_CHUNK_SIZE_INVALID", "单分片大小必须在 1B 到 8MB 之间");
        }
        if (request.totalChunks() <= 0 || request.totalChunks() > MAX_CHUNKS) {
            throw new BusinessException("UPLOAD_CHUNK_COUNT_INVALID", "上传分片数量超出允许范围");
        }
        long maximumDeclaredCapacity = (long) request.chunkSize() * request.totalChunks();
        if (request.fileSize() > maximumDeclaredCapacity) {
            throw new BusinessException("UPLOAD_SIZE_PLAN_INVALID", "分片容量小于声明文件大小");
        }
        String extension = extension(fileName);
        String expectedMime = EXTENSION_MIME.get(extension);
        String actualMime = normalizeMime(request.mimeType());
        if (expectedMime == null || !expectedMime.equals(actualMime)) {
            throw new BusinessException(
                    "UPLOAD_FILE_TYPE_UNSUPPORTED",
                    "仅支持 pdf、md、txt、json、csv 资料，且 MIME 必须匹配扩展名"
            );
        }
    }

    public void validateChunk(int chunkIndex, int totalChunks, int configuredChunkSize, byte[] content) {
        int actualLength = content == null ? 0 : content.length;
        if (actualLength == 0) {
            throw new BusinessException("UPLOAD_CHUNK_EMPTY", "上传分片不能为空");
        }
        if (actualLength > configuredChunkSize || actualLength > MAX_CHUNK_SIZE) {
            throw new BusinessException("UPLOAD_CHUNK_TOO_LARGE", "上传分片超过声明大小");
        }
    }

    public void validateMergedContent(String mimeType, long declaredSize, byte[] content) {
        if (content == null || content.length != declaredSize) {
            throw new BusinessException("UPLOAD_FILE_SIZE_MISMATCH", "合并文件大小与声明不一致");
        }
        if (content.length > MAX_FILE_SIZE) {
            throw new BusinessException("UPLOAD_FILE_TOO_LARGE", "单文件最大允许 128MB");
        }
        String normalizedMime = normalizeMime(mimeType);
        if ("application/pdf".equals(normalizedMime)) {
            if (!hasPdfSignature(content)) {
                throw new BusinessException("UPLOAD_PDF_SIGNATURE_INVALID", "PDF 文件头无效");
            }
            return;
        }
        if (containsNul(content)) {
            throw new BusinessException("UPLOAD_BINARY_CONTENT_REJECTED", "文本资料不能包含 NUL 字节");
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
        } catch (Exception ex) {
            throw new BusinessException("UPLOAD_TEXT_ENCODING_INVALID", "文本资料必须使用有效 UTF-8 编码");
        }
        if (!EXTENSION_MIME.containsValue(normalizedMime)) {
            throw new BusinessException("UPLOAD_FILE_TYPE_UNSUPPORTED", "不支持该文件类型");
        }
    }

    private boolean hasPdfSignature(byte[] content) {
        return content.length >= 5
                && content[0] == '%'
                && content[1] == 'P'
                && content[2] == 'D'
                && content[3] == 'F'
                && content[4] == '-';
    }

    private boolean containsNul(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private String extension(String fileName) {
        int separator = fileName.lastIndexOf('.');
        return separator < 0 ? "" : fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeMime(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        return mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}
