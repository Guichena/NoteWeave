package com.noteweave.common.security;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public CurrentUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser currentUser)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "User is not authenticated");
        }
        return currentUser;
    }

    public Long getCurrentUserId() {
        return getCurrentUser().userId();
    }
}
