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
@Table(name = "user_memory")
public class UserMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "preferences_json", columnDefinition = "TEXT")
    private String preferencesJson;

    @Column(name = "style_profile_json", columnDefinition = "TEXT")
    private String styleProfileJson;

    @Column(name = "habit_profile_json", columnDefinition = "TEXT")
    private String habitProfileJson;

    @Column(name = "memory_write_enabled", nullable = false)
    private boolean memoryWriteEnabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
