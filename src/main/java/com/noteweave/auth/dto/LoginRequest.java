package com.noteweave.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @NotBlank
    @Size(max = 128)
    private String usernameOrEmail;

    @NotBlank
    @Size(min = 8, max = 128)
    private String password;
}
