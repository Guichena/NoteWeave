package com.noteweave.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 160) String login,
        @NotBlank @Size(min = 8, max = 256) String password
) {
}
