package com.noteweave.citation.repository;

import com.noteweave.citation.model.Citation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CitationRepository extends JpaRepository<Citation, Long> {

    List<Citation> findBySpaceIdAndChunkIdIn(Long spaceId, Collection<Long> chunkIds);

    Optional<Citation> findBySpaceIdAndSourceTypeAndSourceIdAndChunkId(Long spaceId, String sourceType, Long sourceId, Long chunkId);
}
