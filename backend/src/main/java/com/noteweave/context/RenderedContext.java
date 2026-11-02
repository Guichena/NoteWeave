package com.noteweave.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 渲染结果：文本、摘要与 token 估算一次算定，供提示词组装、快照与计数记录共同使用。
 *
 * @param schemaVersion     结果结构版本
 * @param rendererVersion   渲染实现版本
 * @param text              渲染文本
 * @param textSha256        渲染文本摘要
 * @param estimatedTokens   渲染文本的估算 token，含分段标题与包装
 * @param estimatorVersion  token 估算方式版本
 * @param sectionTokens     各层条目本身的估算 token，不含标题
 */
public record RenderedContext(
        String schemaVersion,
        String rendererVersion,
        String text,
        String textSha256,
        int estimatedTokens,
        String estimatorVersion,
        Map<String, Integer> sectionTokens
) {

    /** 结果结构版本。 */
    public static final String SCHEMA_VERSION = "rendered-context-v1";

    public RenderedContext {
        text = text == null ? "" : text;
        sectionTokens = sectionTokens == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(sectionTokens));
        if (!SCHEMA_VERSION.equals(schemaVersion) || blank(rendererVersion) || blank(estimatorVersion)
                || estimatedTokens < 0 || !sha256(text).equals(textSha256)) {
            throw new IllegalArgumentException("rendered context is inconsistent");
        }
    }

    /** 是否渲染出了任何正文。 */
    public boolean isEmpty() {
        return text.isBlank();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
