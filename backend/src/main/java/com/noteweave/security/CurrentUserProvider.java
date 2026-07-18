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

    private final boolean localFallbackEnabled;

    public CurrentUserProvider(@Value("${noteweave.security.local-user-fallback:true}") boolean localFallbackEnabled) {
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
        return Optional.empty();
    }

    public boolean isLocalFallbackEnabled() {
        return localFallbackEnabled;
    }
}
