package com.noteweave.auth.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {
    private String token;
    private String tokenType;
    private long expiresIn;
    private AuthUserResponse user;
}
