package com.noteweave.space.repository;

import com.noteweave.space.model.Space;
import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceRepository extends JpaRepository<Space, Long> {

    Optional<Space> findByIdAndStatus(Long id, SpaceStatus status);

    List<Space> findByOwnerIdAndType(Long ownerId, SpaceType type);

    boolean existsByOwnerIdAndType(Long ownerId, SpaceType type);

    List<Space> findByIdInAndStatus(Collection<Long> ids, SpaceStatus status);
}
