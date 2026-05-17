package com.noteweave.memory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@Entity
@Table(name = "memory_item")
public class MemoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "space_id")
    private Long spaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_type", nullable = false, length = 32)
    private MemoryType memoryType;

    @Column(nullable = false, length = 128)
    private String topic;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "source_type", length = 64)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "importance_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal importanceScore = BigDecimal.valueOf(0.5d);

    @Column(name = "confidence_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidenceScore = BigDecimal.valueOf(0.5d);

    @Column(nullable = false)
    private boolean pin;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
