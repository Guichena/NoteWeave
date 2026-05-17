package com.noteweave.memory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@Entity
@Table(name = "space_memory")
public class SpaceMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(length = 128)
    private String topic;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "focused_sources_json", columnDefinition = "TEXT")
    private String focusedSourcesJson;

    @Column(name = "resolved_entities_json", columnDefinition = "TEXT")
    private String resolvedEntitiesJson;

    @Column(name = "artifact_preferences_json", columnDefinition = "TEXT")
    private String artifactPreferencesJson;

    @Column(name = "conversation_patterns_json", columnDefinition = "TEXT")
    private String conversationPatternsJson;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
