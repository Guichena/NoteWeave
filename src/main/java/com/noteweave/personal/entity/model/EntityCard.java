package com.noteweave.personal.entity.model;

import com.noteweave.personal.card.model.PersonalCardStatus;
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
@Table(name = "entity_card")
public class EntityCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "research_project_id")
    private Long researchProjectId;

    @Column(name = "canonical_name", nullable = false, length = 255)
    private String canonicalName;

    @Column(name = "normalized_name", nullable = false, length = 255)
    private String normalizedName;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 32)
    private EntityType entityType = EntityType.OTHER;

    @Column(name = "aliases_json", columnDefinition = "TEXT")
    private String aliasesJson;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "external_refs_json", columnDefinition = "TEXT")
    private String externalRefsJson;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence = BigDecimal.valueOf(0.5d);

    @Enumerated(EnumType.STRING)
    @Column(name = "card_status", nullable = false, length = 32)
    private PersonalCardStatus cardStatus = PersonalCardStatus.READY;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
