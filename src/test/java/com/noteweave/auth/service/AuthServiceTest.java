package com.noteweave.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.auth.dto.AuthResponse;
import com.noteweave.auth.dto.LoginRequest;
import com.noteweave.auth.dto.RegisterRequest;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.common.security.JwtService;
import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceMember;
import com.noteweave.space.model.SpaceRole;
import com.noteweave.space.repository.SpaceMemberRepository;
import com.noteweave.space.repository.SpaceRepository;
import com.noteweave.user.model.User;
import com.noteweave.user.model.UserStatus;
import com.noteweave.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SpaceRepository spaceRepository;
    @Mock
    private SpaceMemberRepository spaceMemberRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    @Test
    void register_ShouldCreateUserAndPersonalSpaceAndOwnerMember() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("alice");
        request.setEmail("alice@example.com");
        request.setPassword("Password123!");
        request.setDisplayName("Alice");

        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });
        when(spaceRepository.save(any(Space.class))).thenAnswer(invocation -> {
            Space saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        when(jwtService.generateToken(any(User.class))).thenReturn("jwt-token");
        when(jwtService.getExpirationSeconds()).thenReturn(86400L);

        AuthResponse response = authService.register(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUser().getUsername()).isEqualTo("alice");

        ArgumentCaptor<SpaceMember> memberCaptor = ArgumentCaptor.forClass(SpaceMember.class);
        verify(spaceMemberRepository).save(memberCaptor.capture());
        SpaceMember member = memberCaptor.getValue();
        assertThat(member.getUserId()).isEqualTo(1L);
        assertThat(member.getSpaceId()).isEqualTo(10L);
        assertThat(member.getRole()).isEqualTo(SpaceRole.OWNER);
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
}
