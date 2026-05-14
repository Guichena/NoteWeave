package com.noteweave.user.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.user.dto.ChangePasswordRequest;
import com.noteweave.user.dto.UpdateUserProfileRequest;
import com.noteweave.user.dto.UserProfileResponse;
import com.noteweave.user.model.User;
import com.noteweave.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserProfileResponse getMe(Long userId) {
        return toProfileResponse(getRequiredUser(userId));
    }

    @Transactional
    public UserProfileResponse updateMe(Long userId, UpdateUserProfileRequest request) {
        User user = getRequiredUser(userId);
        user.setDisplayName(request.getDisplayName());
        user.setAvatarUrl(request.getAvatarUrl());
        return toProfileResponse(userRepository.save(user));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = getRequiredUser(userId);
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    public User getRequiredUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "User not found"));
    }

    private UserProfileResponse toProfileResponse(User user) {
        return UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .avatarUrl(user.getAvatarUrl())
                .systemRole(user.getSystemRole())
                .status(user.getStatus())
                .lastLoginAt(user.getLastLoginAt())
                .disabledAt(user.getDisabledAt())
                .build();
    }
}
