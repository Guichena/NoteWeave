package com.noteweave.config;

import com.noteweave.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);
    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    public static final String REQUEST_HEADER = "X-Request-ID";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        String correlationId = safe(request.getHeader(CORRELATION_HEADER));
        if (correlationId == null) {
            correlationId = safe(request.getHeader(REQUEST_HEADER));
        }
        if (correlationId == null) {
            correlationId = requestId;
        }
        MDC.put(RequestContext.REQUEST_ID, requestId);
        MDC.put(RequestContext.CORRELATION_ID, correlationId);
        putPathContext(request.getRequestURI());
        response.setHeader(REQUEST_HEADER, requestId);
        response.setHeader(CORRELATION_HEADER, correlationId);
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            MDC.put("http_status", Integer.toString(response.getStatus()));
            MDC.put("http_duration_ms", Long.toString(elapsedMs));
            log.info("HTTP request completed: method={}, path={}, status={}, durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs);
            MDC.clear();
        }
    }

    private void putPathContext(String path) {
        String[] segments = path == null ? new String[0] : path.split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            switch (segments[i]) {
                case "workspaces" -> MDC.put(RequestContext.WORKSPACE_ID, safe(segments[i + 1]));
                case "tasks" -> MDC.put(RequestContext.TASK_ID, safe(segments[i + 1]));
                case "sources" -> MDC.put(RequestContext.SOURCE_ID, safe(segments[i + 1]));
                case "answer-runs" -> MDC.put(RequestContext.ANSWER_RUN_ID, safe(segments[i + 1]));
                default -> {
                }
            }
        }
    }

    private String safe(String value) {
        return value != null && SAFE_ID.matcher(value).matches() ? value : null;
    }
}
