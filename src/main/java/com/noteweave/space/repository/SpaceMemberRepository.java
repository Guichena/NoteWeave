package com.noteweave.space.repository;

import com.noteweave.space.model.SpaceMember;
import com.noteweave.space.model.SpaceMemberStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceMemberRepository extends JpaRepository<SpaceMember, Long> {

    Optional<SpaceMember> findBySpaceIdAndUserIdAndStatus(Long spaceId, Long userId, SpaceMemberStatus status);

    List<SpaceMember> findByUserIdAndStatus(Long userId, SpaceMemberStatus status);

    List<SpaceMember> findBySpaceIdAndStatus(Long spaceId, SpaceMemberStatus status);

    boolean existsBySpaceIdAndUserIdAndStatus(Long spaceId, Long userId, SpaceMemberStatus status);

    Optional<SpaceMember> findByIdAndSpaceIdAndStatus(Long id, Long spaceId, SpaceMemberStatus status);

    Optional<SpaceMember> findBySpaceIdAndUserId(Long spaceId, Long userId);
}
