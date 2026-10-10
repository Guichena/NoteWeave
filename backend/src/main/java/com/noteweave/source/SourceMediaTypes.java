package com.noteweave.source;

import java.util.Locale;
import java.util.Map;

/**
 * 可以作为资料上传的音视频类型。音视频资料在解析阶段交给 Artifact Worker，
 * 通过 MCP 转写工具生成带时间戳的文字稿，之后和文本资料一样切片、向量化和索引。
 */
public final class SourceMediaTypes {

    /** 扩展名到规范 MIME 的映射，与前端上传时使用的映射一致。 */
    public static final Map<String, String> EXTENSION_MIME = Map.of(
            "mp3", "audio/mpeg",
            "m4a", "audio/mp4",
            "wav", "audio/wav",
            "ogg", "audio/ogg",
            "flac", "audio/flac",
            "webm", "audio/webm",
            "mp4", "video/mp4"
    );

    private SourceMediaTypes() {
    }

    public static boolean isMedia(String mimeType) {
        return EXTENSION_MIME.containsValue(normalize(mimeType));
    }

    /** 按文件头判断内容是否与声明的音视频类型相符，避免把任意二进制文件当作音频交给转写。 */
    public static boolean hasSignature(String mimeType, byte[] head) {
        if (head == null) return false;
        return switch (normalize(mimeType)) {
            case "audio/mpeg" -> startsWith(head, "ID3")
                    || (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0);
            case "audio/wav" -> startsWith(head, "RIFF") && head.length >= 12
                    && new String(head, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1).equals("WAVE");
            case "audio/mp4", "video/mp4" -> head.length >= 8
                    && new String(head, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1).equals("ftyp");
            case "audio/ogg" -> startsWith(head, "OggS");
            case "audio/flac" -> startsWith(head, "fLaC");
            case "audio/webm" -> head.length >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                    && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3;
            default -> false;
        };
    }

    private static boolean startsWith(byte[] head, String prefix) {
        if (head.length < prefix.length()) return false;
        for (int index = 0; index < prefix.length(); index++) {
            if (head[index] != (byte) prefix.charAt(index)) return false;
        }
        return true;
    }

    public static String normalize(String mimeType) {
        return mimeType == null ? "" : mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
}
