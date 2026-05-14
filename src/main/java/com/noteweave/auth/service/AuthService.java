package com.noteweave.auth.service;

import com.noteweave.auth.dto.AuthResponse;
import com.noteweave.auth.dto.AuthUserResponse;
import com.noteweave.auth.dto.LoginRequest;
import com.noteweave.auth.dto.RegisterRequest;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.common.security.JwtService;
import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceMember;
import com.noteweave.space.model.SpaceMemberStatus;
import com.noteweave.space.model.SpaceRole;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import com.noteweave.space.repository.SpaceMemberRepository;
import com.noteweave.space.repository.SpaceRepository;
import com.noteweave.user.model.User;
import com.noteweave.user.model.UserStatus;
import com.noteweave.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final SpaceRepository spaceRepository;
    private final SpaceMemberRepository spaceMemberRepository;
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
        user.setStatus(UserStatus.ACTIVE);
        user = userRepository.save(user);

        Space personalSpace = new Space();
        personalSpace.setName(user.getUsername() + "'s Personal Space");
        personalSpace.setType(SpaceType.PERSONAL);
        personalSpace.setOwnerId(user.getId());
        personalSpace.setDescription("Personal workspace");
        personalSpace.setStatus(SpaceStatus.ACTIVE);
        personalSpace = spaceRepository.save(personalSpace);

        SpaceMember ownerMember = new SpaceMember();
        ownerMember.setSpaceId(personalSpace.getId());
        ownerMember.setUserId(user.getId());
        ownerMember.setRole(SpaceRole.OWNER);
        ownerMember.setStatus(SpaceMemberStatus.ACTIVE);
        spaceMemberRepository.save(ownerMember);

        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        String usernameOrEmail = request.getUsernameOrEmail().trim();
        User user = userRepository.findByUsername(usernameOrEmail)
                .or(() -> userRepository.findByEmail(usernameOrEmail.toLowerCase()))
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(user);
        return AuthResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationSeconds())
                .user(AuthUserResponse.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .displayName(user.getDisplayName())
                        .build())
                .build();
    }
}
