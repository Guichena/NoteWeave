package com.noteweave.security;

import com.noteweave.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;

@Component
public class ApiRequestIdentityFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbcTemplate;
    private final boolean localFallbackEnabled;

    public ApiRequestIdentityFilter(
            JdbcTemplate jdbcTemplate,
            @Value("${noteweave.security.local-user-fallback:false}") boolean localFallbackEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.localFallbackEnabled = localFallbackEnabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v2/")
                || path.startsWith("/api/v2/actuator")
                || path.equals("/api/v2/auth/login")
                || path.equals("/api/v2/auth/refresh");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim() : "";
        String userId = token.isBlank() ? null : findUserId(token);
        if (userId == null && !token.isBlank()) {
            reject(response, "AUTHENTICATION_INVALID", "用户会话无效或已过期");
            return;
        }
        if (userId == null && !localFallbackEnabled) {
            reject(response, "AUTHENTICATION_REQUIRED", "需要 Bearer 用户会话");
            return;
        }
        if (userId == null) {
            userId = CurrentUserProvider.LOCAL_USER_ID;
        }
        if (userId != null) {
            request.setAttribute(CurrentUserProvider.REQUEST_USER_ID, userId);
            MDC.put(RequestContext.USER_ID, userId);
        }
        filterChain.doFilter(request, response);
    }

    private String findUserId(String token) {
        return jdbcTemplate.query("""
                select s.user_id
                from user_session s
                join users u on u.id = s.user_id
                where s.token_hash = ? and s.status = 'ACTIVE' and s.revoked_at is null
                  and u.status = 'ACTIVE' and (s.expires_at is null or s.expires_at > current_timestamp)
                """, rs -> rs.next() ? rs.getString("user_id") : null, TokenHasher.sha256(token));
    }

    private void reject(HttpServletResponse response, String code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"request_id\":\"" + RequestContext.currentRequestId() + "\"}");
    }
}
