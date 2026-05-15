package com.noteweave.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.auth.dto.AuthResponse;
import com.noteweave.auth.dto.LoginRequest;
import com.noteweave.auth.dto.RefreshTokenRequest;
import com.noteweave.auth.dto.RegisterRequest;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.common.security.JwtService;
import com.noteweave.space.service.SpaceService;
import com.noteweave.user.model.User;
import com.noteweave.user.model.UserSession;
import com.noteweave.user.model.UserStatus;
import com.noteweave.user.repository.UserSessionRepository;
import com.noteweave.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SpaceService spaceService;
    @Mock
    private UserSessionRepository userSessionRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    @Test
    void register_ShouldCreateUserAndPersonalSpace() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(" alice ");
        request.setEmail("ALICE@example.com");
        request.setPassword("Password123!");
        request.setDisplayName(" Alice ");

        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("jwt-token");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(900L);
        when(jwtService.getRefreshTokenExpirationSeconds()).thenReturn(1209600L);
        when(userSessionRepository.save(any(UserSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse response = authService.register(request);

        assertThat(response.getAccessToken()).isEqualTo("jwt-token");
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getUser().getUsername()).isEqualTo("alice");
        verify(spaceService).createPersonalSpace(1L);
        verify(userSessionRepository).save(any(UserSession.class));
        verify(userRepository).existsByUsername("alice");
        verify(userRepository).existsByEmail("alice@example.com");
    }

    @Test
    void register_ShouldFailWhenUsernameExists() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setEmail("alice@example.com");
        request.setPassword("Password123!");

        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getErrorCode() == ErrorCode.USER_ALREADY_EXISTS);
        verify(spaceService, never()).createPersonalSpace(any());
    }

    @Test
    void login_ShouldFailWhenPasswordIsWrong() {
        LoginRequest request = new LoginRequest();
        request.setUsernameOrEmail("alice");
        request.setPassword("Password123!");

        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPasswordHash("hashed-password");
        user.setStatus(UserStatus.ACTIVE);

        when(userRepository.findByUsername(eq("alice"))).thenReturn(java.util.Optional.of(user));
        when(passwordEncoder.matches("Password123!", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getErrorCode() == ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void refresh_ShouldTrimRefreshTokenBeforeLookup() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(" test-refresh-token ");

        UserSession session = new UserSession();
        session.setUserId(1L);
        session.setExpiresAt(LocalDateTime.now().plusDays(1));

        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setStatus(UserStatus.ACTIVE);

        when(userSessionRepository.findByRefreshTokenHashAndRevokedAtIsNull(any())).thenReturn(Optional.of(session));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("jwt-token");
        when(jwtService.getAccessTokenExpirationSeconds()).thenReturn(900L);
        when(jwtService.getRefreshTokenExpirationSeconds()).thenReturn(1209600L);
        when(userSessionRepository.save(any(UserSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse response = authService.refresh(request);

        assertThat(response.getAccessToken()).isEqualTo("jwt-token");
        verify(userSessionRepository).findByRefreshTokenHashAndRevokedAtIsNull(any());
    }
}
