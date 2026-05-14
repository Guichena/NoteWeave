package com.noteweave.user.controller;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.common.security.CurrentUserProvider;
import com.noteweave.user.dto.ChangePasswordRequest;
import com.noteweave.user.dto.UpdateUserProfileRequest;
import com.noteweave.user.dto.UserProfileResponse;
import jakarta.validation.Valid;
import com.noteweave.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getMe() {
        Long userId = currentUserProvider.getCurrentUserId();
        return ApiResponse.success(userService.getMe(userId));
    }

    @PutMapping("/me")
    public ApiResponse<UserProfileResponse> updateMe(@Valid @RequestBody UpdateUserProfileRequest request) {
        Long userId = currentUserProvider.getCurrentUserId();
        return ApiResponse.success(userService.updateMe(userId, request));
    }

    @PutMapping("/me/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Long userId = currentUserProvider.getCurrentUserId();
        userService.changePassword(userId, request);
        return ApiResponse.success(null);
    }
}
