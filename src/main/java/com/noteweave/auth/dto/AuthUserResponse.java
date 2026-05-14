package com.noteweave.auth.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthUserResponse {
    private Long id;
    private String username;
    private String displayName;
}
