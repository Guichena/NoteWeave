package com.noteweave.user.dto;

import com.noteweave.user.model.UserStatus;
import com.noteweave.user.model.UserSystemRole;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserProfileResponse {
    private Long id;
    private String username;
    private String email;
    private String displayName;
    private String avatarUrl;
    private UserSystemRole systemRole;
    private UserStatus status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime disabledAt;
}
