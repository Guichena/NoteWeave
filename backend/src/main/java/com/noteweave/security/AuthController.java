package com.noteweave.security;

import com.noteweave.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserProvider currentUserProvider;

    public AuthController(AuthService authService, CurrentUserProvider currentUserProvider) {
        this.authService = authService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/register")
    ApiResponse<AuthSessionResponse> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(authService.register(
                request.username(), request.email(), request.password(), request.displayName(),
                httpRequest.getRemoteAddr()));
    }

    @PostMapping("/login")
    ApiResponse<AuthSessionResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(authService.login(
                request.login().trim(), request.password(), httpRequest.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    ApiResponse<AuthSessionResponse> refresh(@Valid @RequestBody RefreshSessionRequest request) {
        return ApiResponse.success(authService.refresh(request.refreshToken().trim()));
    }

    @PostMapping("/logout")
    ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(requireBearer(request));
        return ApiResponse.success(null);
    }

    @GetMapping("/session")
    ApiResponse<AuthUserResponse> session() {
        return ApiResponse.success(authService.getUser(currentUserProvider.requireUserId()));
    }

    private String requireBearer(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization == null ? "" : authorization.replaceFirst("^Bearer\\s+", "").trim();
    }
}
