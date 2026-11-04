package com.noteweave.security;

import com.noteweave.common.BusinessException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class CurrentUserProvider {

    public static final String REQUEST_USER_ID = CurrentUserProvider.class.getName() + ".userId";
    public static final String LOCAL_USER_ID = "local-user";

    /**
     * 后台线程代表某个已认证用户继续执行请求时使用的用户标识。
     * 只由 {@link #runAs} 设置，请求上下文存在时以请求上下文为准。
     */
    private static final ThreadLocal<String> DELEGATED_USER_ID = new ThreadLocal<>();

    private final boolean localFallbackEnabled;

    public CurrentUserProvider(@Value("${noteweave.security.local-user-fallback:false}") boolean localFallbackEnabled) {
        this.localFallbackEnabled = localFallbackEnabled;
    }

    public String requireUserId() {
        return currentUserId().orElseGet(() -> {
            if (localFallbackEnabled) {
                return LOCAL_USER_ID;
            }
            throw new BusinessException("AUTHENTICATION_REQUIRED", "需要有效的用户会话", HttpStatus.UNAUTHORIZED);
        });
    }

    public Optional<String> currentUserId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            Object userId = servletAttributes.getRequest().getAttribute(REQUEST_USER_ID);
            if (userId instanceof String value && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        String delegated = DELEGATED_USER_ID.get();
        return delegated == null || delegated.isBlank() ? Optional.empty() : Optional.of(delegated);
    }

    /**
     * 在后台线程里以发起请求的用户身份执行。调用方必须在请求线程里取得这个标识，
     * 也就是用户已经通过认证；userId 为空时按无用户上下文执行（本地回退或系统身份）。
     */
    public static void runAs(String userId, Runnable action) {
        String previous = DELEGATED_USER_ID.get();
        if (userId == null || userId.isBlank()) {
            DELEGATED_USER_ID.remove();
        } else {
            DELEGATED_USER_ID.set(userId);
        }
        try {
            action.run();
        } finally {
            if (previous == null) {
                DELEGATED_USER_ID.remove();
            } else {
                DELEGATED_USER_ID.set(previous);
            }
        }
    }

    public boolean isLocalFallbackEnabled() {
        return localFallbackEnabled;
    }
}
