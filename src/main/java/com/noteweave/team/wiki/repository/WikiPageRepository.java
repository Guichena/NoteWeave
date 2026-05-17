package com.noteweave.team.wiki.repository;

import com.noteweave.team.wiki.model.WikiPage;
import com.noteweave.team.wiki.model.WikiPageStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface WikiPageRepository extends JpaRepository<WikiPage, Long> {

    List<WikiPage> findBySpaceIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long spaceId);

    List<WikiPage> findBySpaceIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(Long spaceId, WikiPageStatus status);

    Optional<WikiPage> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from WikiPage p where p.id = :id")
    Optional<WikiPage> findByIdForUpdate(Long id);
}
