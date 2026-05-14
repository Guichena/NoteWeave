package com.noteweave.user.repository;

import com.noteweave.user.model.UserSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByRefreshTokenHashAndRevokedAtIsNull(String refreshTokenHash);

    Optional<UserSession> findByUserIdAndRefreshTokenHashAndRevokedAtIsNull(Long userId, String refreshTokenHash);

    List<UserSession> findByUserIdAndRevokedAtIsNull(Long userId);
}
