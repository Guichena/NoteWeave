package com.noteweave.prompt.repository;

import com.noteweave.prompt.model.PromptVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptVersionRepository extends JpaRepository<PromptVersion, Long> {

    Optional<PromptVersion> findBySceneAndStatus(String scene, String status);

    List<PromptVersion> findBySceneOrderByVersionDesc(String scene);

    List<PromptVersion> findAllByOrderByCreatedAtDesc();

    List<PromptVersion> findAllBySceneAndStatus(String scene, String status);

    Optional<PromptVersion> findTopBySceneOrderByVersionDesc(String scene);
}
