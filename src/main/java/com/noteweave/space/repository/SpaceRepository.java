package com.noteweave.space.repository;

import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceMemberStatus;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpaceRepository extends JpaRepository<Space, Long> {

    Optional<Space> findByIdAndStatus(Long id, SpaceStatus status);

    List<Space> findByOwnerIdAndType(Long ownerId, SpaceType type);

    boolean existsByOwnerIdAndType(Long ownerId, SpaceType type);

    List<Space> findByIdInAndStatus(Collection<Long> ids, SpaceStatus status);

    Optional<Space> findFirstByOwnerIdAndTypeAndStatusOrderByIdAsc(Long ownerId, SpaceType type, SpaceStatus status);

    @Query("""
            SELECT s FROM Space s
            WHERE s.status = :spaceStatus
              AND EXISTS (
                  SELECT 1 FROM SpaceMember sm
                  WHERE sm.spaceId = s.id
                    AND sm.userId = :userId
                    AND sm.status = :memberStatus
              )
            """)
    Page<Space> findVisibleSpaces(
            @Param("userId") Long userId,
            @Param("spaceStatus") SpaceStatus spaceStatus,
            @Param("memberStatus") SpaceMemberStatus memberStatus,
            Pageable pageable
    );
}
