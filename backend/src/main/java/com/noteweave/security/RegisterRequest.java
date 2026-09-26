package com.noteweave.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 80)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
        String username,
        @NotBlank @Email @Size(max = 160) String email,
        @NotBlank @Size(min = 8, max = 256) String password,
        @Size(max = 120) String displayName
) {
}
