package com.noteweave.personal.claim.repository;

import com.noteweave.personal.claim.model.Claim;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findByResearchQuestionIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long researchQuestionId);

    List<Claim> findByResearchProjectIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long researchProjectId);

    List<Claim> findByIdInAndDeletedAtIsNull(Collection<Long> ids);

    List<Claim> findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long userId);

    boolean existsByWritebackKeyAndDeletedAtIsNull(String writebackKey);

    Optional<Claim> findByIdAndSpaceIdAndDeletedAtIsNull(Long id, Long spaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id")
    Optional<Claim> findByIdForUpdate(Long id);
}
