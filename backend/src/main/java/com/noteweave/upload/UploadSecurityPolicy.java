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
        String expectedMime = EXTENSION_MIME.getOrDefault(extension,
                com.noteweave.source.SourceMediaTypes.EXTENSION_MIME.get(extension));
        String actualMime = normalizeMime(request.mimeType());
        if (expectedMime == null || !expectedMime.equals(actualMime)) {
            throw new BusinessException(
                    "UPLOAD_FILE_TYPE_UNSUPPORTED",
                    "仅支持 pdf、md、txt、json、csv 文档和 mp3、m4a、wav、ogg、flac、webm、mp4 音视频，且 MIME 必须匹配扩展名"
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
        if (com.noteweave.source.SourceMediaTypes.isMedia(normalizedMime)) {
            requireMediaSignature(normalizedMime, java.util.Arrays.copyOf(content, Math.min(content.length, 16)));
            return;
        }
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
            requireSupportedTextEncoding(new java.io.ByteArrayInputStream(content),
                    () -> new java.io.ByteArrayInputStream(content));
        } catch (java.io.IOException ex) {
            throw new BusinessException("UPLOAD_MERGE_FAILED", "上传分片合并失败");
        }
        if (!EXTENSION_MIME.containsValue(normalizedMime)) {
            throw new BusinessException("UPLOAD_FILE_TYPE_UNSUPPORTED", "不支持该文件类型");
        }
    }

    /**
     * 与 {@link #validateMergedContent} 规则相同，但按块读取已合并到本地的文件，
     * 128MB 的文件也只占用固定大小的缓冲区。
     */
    public void validateMergedFile(String mimeType, long declaredSize, java.nio.file.Path file) {
        long actualSize;
        try {
            actualSize = java.nio.file.Files.size(file);
        } catch (java.io.IOException ex) {
            throw new BusinessException("UPLOAD_MERGE_FAILED", "上传分片合并失败");
        }
        if (actualSize != declaredSize) {
            throw new BusinessException("UPLOAD_FILE_SIZE_MISMATCH", "合并文件大小与声明不一致");
        }
        if (actualSize > MAX_FILE_SIZE) {
            throw new BusinessException("UPLOAD_FILE_TOO_LARGE", "单文件最大允许 128MB");
        }
        String normalizedMime = normalizeMime(mimeType);
        try (java.io.InputStream input = java.nio.file.Files.newInputStream(file)) {
            if (com.noteweave.source.SourceMediaTypes.isMedia(normalizedMime)) {
                // 音视频是二进制内容，只核对文件头
                requireMediaSignature(normalizedMime, input.readNBytes(16));
                return;
            }
            if ("application/pdf".equals(normalizedMime)) {
                if (!hasPdfSignature(input.readNBytes(5))) {
                    throw new BusinessException("UPLOAD_PDF_SIGNATURE_INVALID", "PDF 文件头无效");
                }
                return;
            }
            requireSupportedTextEncoding(input, () -> java.nio.file.Files.newInputStream(file));
        } catch (java.io.IOException ex) {
            throw new BusinessException("UPLOAD_MERGE_FAILED", "上传分片合并失败");
        }
        if (!EXTENSION_MIME.containsValue(normalizedMime)) {
            throw new BusinessException("UPLOAD_FILE_TYPE_UNSUPPORTED", "不支持该文件类型");
        }
    }

    private static final java.nio.charset.Charset GB18030 = java.nio.charset.Charset.forName("GB18030");
    private static final String TEXT_ENCODING_MESSAGE = "文本资料必须使用 UTF-8 或 GBK（GB18030）编码";

    @FunctionalInterface
    private interface InputReopener {
        java.io.InputStream open() throws java.io.IOException;
    }

    private enum TextDecodeResult { VALID, TRUNCATED_AT_END, MALFORMED }

    /**
     * 文本资料接受 UTF-8 与 GB18030（兼容 GBK、GB2312）。先按 UTF-8 严格解码；
     * 只在末尾缺字节时判定为被截断的 UTF-8 并拒绝，其他非法字节再按 GB18030 严格解码一次。
     */
    private void requireSupportedTextEncoding(java.io.InputStream input, InputReopener reopen)
            throws java.io.IOException {
        TextDecodeResult utf8 = decodeStrictly(input, StandardCharsets.UTF_8, true);
        if (utf8 == TextDecodeResult.VALID) {
            return;
        }
        if (utf8 == TextDecodeResult.MALFORMED) {
            try (java.io.InputStream again = reopen.open()) {
                if (decodeStrictly(again, GB18030, false) == TextDecodeResult.VALID) {
                    return;
                }
            }
        }
        throw new BusinessException("UPLOAD_TEXT_ENCODING_INVALID", TEXT_ENCODING_MESSAGE);
    }

    /** 分块检查 NUL 字节并严格解码；跨块的多字节字符由解码器保留到下一块。 */
    private TextDecodeResult decodeStrictly(java.io.InputStream input, java.nio.charset.Charset charset,
                                            boolean checkNul) throws java.io.IOException {
        java.nio.charset.CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        byte[] block = new byte[64 * 1024];
        ByteBuffer pending = ByteBuffer.allocate(block.length + 8);
        java.nio.CharBuffer sink = java.nio.CharBuffer.allocate(block.length);
        int read;
        while ((read = input.read(block)) != -1) {
            if (checkNul && containsNul(block, read)) {
                throw new BusinessException("UPLOAD_BINARY_CONTENT_REJECTED", "文本资料不能包含 NUL 字节");
            }
            pending.put(block, 0, read);
            pending.flip();
            if (!decodeInto(decoder, pending, sink, false)) {
                return TextDecodeResult.MALFORMED;
            }
            pending.compact();
        }
        pending.flip();
        if (!decodeInto(decoder, pending, sink, true)) {
            return TextDecodeResult.TRUNCATED_AT_END;
        }
        sink.clear();
        return decoder.flush(sink).isError() ? TextDecodeResult.TRUNCATED_AT_END : TextDecodeResult.VALID;
    }

    private boolean decodeInto(java.nio.charset.CharsetDecoder decoder, ByteBuffer input,
                               java.nio.CharBuffer sink, boolean endOfInput) {
        while (true) {
            sink.clear();
            java.nio.charset.CoderResult result = decoder.decode(input, sink, endOfInput);
            if (result.isError()) {
                return false;
            }
            if (result.isUnderflow()) {
                return true;
            }
        }
    }

    private boolean containsNul(byte[] content, int length) {
        for (int index = 0; index < length; index++) {
            if (content[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private void requireMediaSignature(String mimeType, byte[] head) {
        if (!com.noteweave.source.SourceMediaTypes.hasSignature(mimeType, head)) {
            throw new BusinessException("UPLOAD_MEDIA_SIGNATURE_INVALID", "音视频文件头与声明的格式不符");
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
