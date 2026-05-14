package com.noteweave.user.dto;

import com.noteweave.user.model.UserStatus;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserProfileResponse {
    private Long id;
    private String username;
    private String email;
    private String displayName;
    private UserStatus status;
}
