package com.noteweave.security;

public record AuthSessionResponse(
        String accessToken,
        String refreshToken,
        String accessExpiresAt,
        String refreshExpiresAt,
        AuthUserResponse user
) {
}
