package com.noteweave.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;

/** 使用 MVC 相同的分段解码与路径参数规则，避免安全检查和路由识别不同的资源。 */
public final class RequestPaths {
    private RequestPaths() {}

    public static String applicationPath(HttpServletRequest request) {
        RequestPath path = RequestPath.parse(request.getRequestURI(), request.getContextPath());
        StringBuilder result = new StringBuilder();
        for (PathContainer.Element element : path.pathWithinApplication().elements()) {
            result.append(element instanceof PathContainer.PathSegment segment
                    ? segment.valueToMatch() : element.value());
        }
        return result.toString();
    }
}
