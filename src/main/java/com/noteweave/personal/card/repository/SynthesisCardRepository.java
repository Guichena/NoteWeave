package com.noteweave.personal.card.repository;

import com.noteweave.personal.card.model.SynthesisCard;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SynthesisCardRepository extends JpaRepository<SynthesisCard, Long> {

    Optional<SynthesisCard> findByIdAndSpaceId(Long id, Long spaceId);

    List<SynthesisCard> findByResearchProjectIdAndSpaceIdOrderByUpdatedAtDesc(Long researchProjectId, Long spaceId);

    List<SynthesisCard> findBySpaceIdOrderByUpdatedAtDesc(Long spaceId);
}
