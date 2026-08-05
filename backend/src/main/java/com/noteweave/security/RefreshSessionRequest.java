package com.noteweave.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshSessionRequest(@NotBlank @Size(max = 256) String refreshToken) {
}
