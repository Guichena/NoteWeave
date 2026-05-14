package com.noteweave.auth.controller;

import com.noteweave.auth.dto.AuthResponse;
import com.noteweave.auth.dto.LoginRequest;
import com.noteweave.auth.dto.LogoutRequest;
import com.noteweave.auth.dto.RefreshTokenRequest;
import com.noteweave.auth.dto.RegisterRequest;
import com.noteweave.auth.service.AuthService;
import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(currentUserProvider.getCurrentUserId(), request);
        return ApiResponse.success(null);
    }

    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll() {
        authService.logoutAll(currentUserProvider.getCurrentUserId());
        return ApiResponse.success(null);
    }
}
