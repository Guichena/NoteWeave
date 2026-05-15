package com.noteweave.team.document.repository;

import com.noteweave.team.document.model.UploadChunk;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadChunkRepository extends JpaRepository<UploadChunk, Long> {

    Optional<UploadChunk> findByUploadIdAndChunkIndex(Long uploadId, Integer chunkIndex);

    List<UploadChunk> findByUploadIdOrderByChunkIndexAsc(Long uploadId);

    long countByUploadId(Long uploadId);

    void deleteByUploadId(Long uploadId);
}
