package com.noteweave.memory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "session_summary")
public class SessionSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "research_question_id")
    private Long researchQuestionId;

    @Column(length = 128)
    private String topic;

    @Column(name = "query_type", length = 64)
    private String queryType;

    @Column(name = "scope_type", length = 32)
    private String scopeType;

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "resolved_entities_json", columnDefinition = "TEXT")
    private String resolvedEntitiesJson;

    @Column(name = "reference_source_json", columnDefinition = "TEXT")
    private String referenceSourceJson;

    @Column(name = "importance_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal importanceScore = BigDecimal.valueOf(0.5d);

    @Column(name = "confidence_score", nullable = false, precision = 5, scale = 4)
    private BigDecimal confidenceScore = BigDecimal.valueOf(0.5d);

    @Column(nullable = false)
    private boolean stale;

    @Column(nullable = false)
    private boolean pin;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
