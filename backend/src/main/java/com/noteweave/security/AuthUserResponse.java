package com.noteweave.security;

public record AuthUserResponse(String userId, String username, String email, String displayName) {
}
