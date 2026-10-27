package com.noteweave.config;

import com.noteweave.common.RequestContext;
import com.noteweave.security.ApiPaths;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部服务接口（{@code /internal/*}）的统一鉴权过滤器。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>token 一旦配置，必须校验；缺失 / 不匹配一律 401。</li>
 *   <li>token 未配置（默认 / 开发环境）：
 *     <ul>
 *       <li>启动时打印 WARN 提醒生产环境必须显式配置；</li>
 *       <li>只放行 loopback 调用（127.0.0.1 / ::1 / 0:0:0:0:0:0:0:1），</li>
 *       <li>远端调用一律拒绝，避免默认凭证触发裸奔。</li>
 *     </ul>
 *   </li>
 * </ul>
 * 该过滤器只作用于 {@code /internal/*} 路径，不影响业务 API。
 */
@Component
public class InternalServiceAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-NoteWeave-Internal-Token";

    private static final Logger log = LoggerFactory.getLogger(InternalServiceAuthFilter.class);

    /** 视为 loopback 的远端地址集合。 */
    private static final Set<String> LOOPBACK_ADDRS = Set.of(
            "127.0.0.1", "0:0:0:0:0:0:0:1", "::1", "localhost"
    );

    private final String coordinatorToken;
    private final String researchToken;
    private final String artifactToken;

    public InternalServiceAuthFilter(
            @Value("${noteweave.internal.auth-token:}") String coordinatorToken,
            @Value("${noteweave.internal.research-auth-token:}") String researchToken,
            @Value("${noteweave.internal.artifact-auth-token:}") String artifactToken
    ) {
        this.coordinatorToken = trimmed(coordinatorToken);
        this.researchToken = trimmed(researchToken);
        this.artifactToken = trimmed(artifactToken);
    }

    @PostConstruct
    void warnOnUnsetToken() {
        if (coordinatorToken.isBlank() && researchToken.isBlank() && artifactToken.isBlank()) {
            log.warn("====================================================================");
            log.warn("[InternalServiceAuthFilter] noteweave.internal.auth-token is EMPTY.");
            log.warn("  /internal/* requests will be allowed ONLY from loopback (127.0.0.1 / ::1).");
            log.warn("  Set NOTEWEAVE_INTERNAL_AUTH_TOKEN before deploying to production.");
            log.warn("====================================================================");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 与授权拦截器共用 ApiPaths 规范化路径（解码 %XX、剥离矩阵参数、去掉 contextPath）。
        // 直接用原始 getRequestURI() 判断时，/%69nternal/... 这类等价编码不会命中
        // 前缀检查，请求会跳过本过滤器直达内部路由。
        String path = ApiPaths.canonicalPath(request);
        if (path == null) {
            // 非法百分号编码：无法判定归属，进入 doFilterInternal 统一拒绝
            return false;
        }
        return !isInternalPath(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = ApiPaths.canonicalPath(request);
        if (path == null) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "REQUEST_PATH_INVALID",
                    "请求路径包含非法编码");
            return;
        }
        if (!isInternalPath(path)) {
            // 与 shouldNotFilter 使用同一判定，避免两处口径不一致
            filterChain.doFilter(request, response);
            return;
        }
        String expectedToken = expectedToken(path);
        if (!expectedToken.isBlank()) {
            // 生产 / 配置生效：必须 header 匹配
            String supplied = request.getHeader(HEADER_NAME);
            if (supplied != null && MessageDigest.isEqual(
                    expectedToken.getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8))) {
                filterChain.doFilter(request, response);
                return;
            }
            reject(response, "INTERNAL_AUTH_REQUIRED",
                    "internal service authentication failed");
            return;
        }

        // 未配置 token：仅放行 loopback
        String remote = request.getRemoteAddr();
        if (remote != null && LOOPBACK_ADDRS.contains(remote.toLowerCase())) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("Rejecting /internal/* request from non-loopback remote={} because auth token is unset", remote);
        reject(response, "INTERNAL_AUTH_NOT_CONFIGURED",
                "internal service authentication is not configured; "
                        + "set noteweave.internal.auth-token or call from loopback");
    }

    private String expectedToken(String path) {
        if (isResearchWorkerRoute(path)) {
            return researchToken.isBlank() ? coordinatorToken : researchToken;
        }
        if (isArtifactWorkerRoute(path)) {
            return artifactToken.isBlank() ? coordinatorToken : artifactToken;
        }
        return coordinatorToken;
    }

    private boolean isResearchWorkerRoute(String path) {
        return path.startsWith("/internal/research-agent-tasks/")
                || path.equals("/internal/research-agent/permits")
                || path.equals("/internal/research-agent/workspace-windows/search")
                || path.equals("/internal/research-agent/external-snapshots")
                || path.equals("/internal/research-agent/external-snapshots/archived")
                || path.equals("/internal/research-agent/delivery-failures")
                || path.startsWith("/internal/research-agent/distributed-replay/");
    }

    private boolean isArtifactWorkerRoute(String path) {
        return path.startsWith("/internal/worker/artifact-tasks/")
                || path.startsWith("/internal/worker/video-material-tasks/")
                || path.startsWith("/internal/worker/tasks/")
                || path.startsWith("/internal/worker/source-transcriptions/")
                || path.equals("/internal/worker/artifact-callbacks/acquisition/ack");
    }

    /** 内部接口前缀；{@code /internal} 本身也属于内部命名空间。 */
    private static boolean isInternalPath(String path) {
        return path.equals("/internal") || path.startsWith("/internal/");
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private void reject(HttpServletResponse response, String code, String message) throws IOException {
        reject(response, HttpServletResponse.SC_UNAUTHORIZED, code, message);
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        // 极简 JSON，避开对 ObjectMapper 的依赖
        response.getWriter().write(
                "{\"success\":false,\"code\":\"" + code + "\",\"message\":\""
                        + message.replace("\"", "\\\"") + "\",\"request_id\":\""
                        + RequestContext.currentRequestId() + "\"}"
        );
    }
}
