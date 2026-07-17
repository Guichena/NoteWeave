package com.noteweave.research;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounded raw-body cache for the two legacy result routes whose task identity
 * lives in JSON.  MVC still receives the exact cached bytes for its normal
 * UTF-8/JSON binding and Bean Validation path.
 */
@Component
final class ResearchAgentLegacyResultRawBodyFilter extends OncePerRequestFilter {

    static final String RAW_BODY_ATTRIBUTE = ResearchAgentLegacyResultRawBodyFilter.class.getName() + ".rawBody";
    static final String BODY_TOO_LARGE_ATTRIBUTE = ResearchAgentLegacyResultRawBodyFilter.class.getName() + ".tooLarge";
    static final String BODY_READ_FAILED_ATTRIBUTE = ResearchAgentLegacyResultRawBodyFilter.class.getName() + ".readFailed";

    private static final Set<String> BODY_TASK_ROUTES = Set.of(
            "/internal/research-agent/workspace-evidence-batches",
            "/internal/research-agent/candidate-batches"
    );
    private static final int MAX_BODY_BYTES = ResearchAgentCompletionEnvelopeParser.MAX_PAYLOAD_BYTES;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !BODY_TASK_ROUTES.contains(routePath(request));
    }

    private String routePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length())
                : uri;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        BoundedBody body = readBounded(request);
        CachedBodyRequest wrapped = new CachedBodyRequest(request, body.bytes());
        wrapped.setAttribute(RAW_BODY_ATTRIBUTE, body.bytes());
        wrapped.setAttribute(BODY_TOO_LARGE_ATTRIBUTE, body.tooLarge());
        wrapped.setAttribute(BODY_READ_FAILED_ATTRIBUTE, body.readFailed());
        filterChain.doFilter(wrapped, response);
    }

    private BoundedBody readBounded(HttpServletRequest request) {
        boolean declaredTooLarge = request.getContentLengthLong() > MAX_BODY_BYTES;
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(
                Math.max(request.getContentLength(), 0), MAX_BODY_BYTES));
        byte[] buffer = new byte[8192];
        boolean tooLarge = declaredTooLarge;
        try {
            ServletInputStream input = request.getInputStream();
            int read;
            while ((read = input.read(buffer)) != -1) {
                int remaining = MAX_BODY_BYTES - output.size();
                if (read > remaining) {
                    if (remaining > 0) output.write(buffer, 0, remaining);
                    tooLarge = true;
                    break;
                }
                output.write(buffer, 0, read);
                if (output.size() == MAX_BODY_BYTES) {
                    int overflow = input.read();
                    if (overflow != -1) tooLarge = true;
                    break;
                }
            }
            return new BoundedBody(output.toByteArray(), tooLarge, false);
        } catch (IOException exception) {
            return new BoundedBody(output.toByteArray(), tooLarge, true);
        }
    }

    private record BoundedBody(byte[] bytes, boolean tooLarge, boolean readFailed) { }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    if (listener == null) return;
                    try {
                        if (!isFinished()) listener.onDataAvailable();
                        if (isFinished()) listener.onAllDataRead();
                    } catch (IOException exception) {
                        listener.onError(exception);
                    }
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    return input.read(bytes, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
