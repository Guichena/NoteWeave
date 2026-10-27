package com.noteweave.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * API 路径规范化（NR01）：认证过滤器与授权拦截器必须对同一形式的路径做判断。
 *
 * <p>MVC 路由按解码后、剔除矩阵参数的路径段匹配；安全层此前直接用原始 requestURI
 * 文本匹配，{@code /api;x=1/v2/knowledge-items/{id}} 这类等价路径可以同时避开
 * 认证前缀检查与授权正则后仍到达业务路由。本类统一安全层视角：
 * 去掉 contextPath、只解码 %XX（路径段中的 {@code +} 是字面量，不是空格）、
 * 每段剥掉从首个 {@code ;} 开始的矩阵参数。解码失败返回 null，由调用方按非法请求拒绝。</p>
 */
public final class ApiPaths {

    private ApiPaths() {
    }

    /** 规范化路径；非法百分号编码返回 null。 */
    public static String canonicalPath(HttpServletRequest request) {
        String raw = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && raw.startsWith(context)) {
            raw = raw.substring(context.length());
        }
        String decoded = percentDecode(raw);
        if (decoded == null) {
            return null;
        }
        return stripMatrixParams(decoded);
    }

    /** 只解码 %XX 序列；'+' 保持字面量。非法序列返回 null，不抛异常。 */
    static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder decoded = new StringBuilder(value.length());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '%') {
                flushBytes(decoded, bytes);
                decoded.append(current);
                continue;
            }
            if (i + 2 >= value.length()) {
                return null;
            }
            int high = Character.digit(value.charAt(i + 1), 16);
            int low = Character.digit(value.charAt(i + 2), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes.write((high << 4) | low);
            i += 2;
        }
        flushBytes(decoded, bytes);
        return decoded.toString();
    }

    private static void flushBytes(StringBuilder decoded, java.io.ByteArrayOutputStream bytes) {
        if (bytes.size() > 0) {
            decoded.append(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            bytes.reset();
        }
    }

    /** 每段剥掉从首个 ';' 开始的矩阵参数：{@code /api;x=1/v2} → {@code /api/v2}。 */
    static String stripMatrixParams(String path) {
        if (path.indexOf(';') < 0) {
            return path;
        }
        StringBuilder cleaned = new StringBuilder(path.length());
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            int semicolon = segment.indexOf(';');
            cleaned.append(semicolon >= 0 ? segment.substring(0, semicolon) : segment);
            if (i < segments.length - 1) {
                cleaned.append('/');
            }
        }
        return cleaned.toString();
    }
}
