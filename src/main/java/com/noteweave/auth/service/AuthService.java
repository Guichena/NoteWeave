package com.noteweave.auth.service;

import com.noteweave.auth.dto.AuthResponse;
import com.noteweave.auth.dto.AuthUserResponse;
import com.noteweave.auth.dto.LoginRequest;
import com.noteweave.auth.dto.LogoutRequest;
import com.noteweave.auth.dto.RefreshTokenRequest;
import com.noteweave.auth.dto.RegisterRequest;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.common.security.JwtService;
import com.noteweave.space.service.SpaceService;
import com.noteweave.user.model.User;
import com.noteweave.user.model.UserSession;
import com.noteweave.user.model.UserStatus;
import com.noteweave.user.model.UserSystemRole;
import com.noteweave.user.repository.UserSessionRepository;
import com.noteweave.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final SpaceService spaceService;
    private final UserSessionRepository userSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS, "Username already exists");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS, "Email already exists");
        }

        User user = new User();
        user.setUsername(request.getUsername().trim());
        user.setEmail(request.getEmail().trim().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName());
        user.setSystemRole(UserSystemRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.save(user);

        spaceService.createPersonalSpace(user.getId());

        return issueTokens(user, null, null, null);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String usernameOrEmail = request.getUsernameOrEmail().trim();
        User user = userRepository.findByUsername(usernameOrEmail)
                .or(() -> userRepository.findByEmail(usernameOrEmail.toLowerCase()))
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        return issueTokens(user, null, null, null);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        UserSession session = userSessionRepository.findByRefreshTokenHashAndRevokedAtIsNull(hashRefreshToken(request.getRefreshToken()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Invalid refresh token"));

        LocalDateTime now = LocalDateTime.now();
        if (session.getExpiresAt().isBefore(now)) {
            session.setRevokedAt(now);
            userSessionRepository.save(session);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Refresh token expired");
        }

        User user = userRepository.findById(session.getUserId())
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Invalid refresh token"));

        session.setRevokedAt(now);
        userSessionRepository.save(session);
        return issueTokens(user, session.getDeviceInfo(), session.getIpAddress(), session.getUserAgent());
    }

    @Transactional
    public void logout(Long userId, LogoutRequest request) {
        String tokenHash = hashRefreshToken(request.getRefreshToken());
        Optional<UserSession> session = userSessionRepository.findByUserIdAndRefreshTokenHashAndRevokedAtIsNull(userId, tokenHash);
        if (session.isPresent()) {
            UserSession userSession = session.get();
            userSession.setRevokedAt(LocalDateTime.now());
            userSessionRepository.save(userSession);
        }
    }

    @Transactional
    public void logoutAll(Long userId) {
        List<UserSession> activeSessions = userSessionRepository.findByUserIdAndRevokedAtIsNull(userId);
        if (activeSessions.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        activeSessions.forEach(session -> session.setRevokedAt(now));
        userSessionRepository.saveAll(activeSessions);
    }

    private AuthResponse issueTokens(User user, String deviceInfo, String ipAddress, String userAgent) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = generateRefreshToken();

        UserSession session = new UserSession();
        session.setUserId(user.getId());
        session.setRefreshTokenHash(hashRefreshToken(refreshToken));
        session.setDeviceInfo(deviceInfo);
        session.setIpAddress(ipAddress);
        session.setUserAgent(userAgent);
        session.setExpiresAt(LocalDateTime.now().plusSeconds(jwtService.getRefreshTokenExpirationSeconds()));
        userSessionRepository.save(session);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessTokenExpirationSeconds())
                .user(AuthUserResponse.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .displayName(user.getDisplayName())
                        .build())
                .build();
    }

    private String generateRefreshToken() {
        byte[] randomBytes = new byte[48];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private String hashRefreshToken(String refreshToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
