package com.noteweave.personal.card.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "synthesis_card")
public class SynthesisCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "research_project_id", nullable = false)
    private Long researchProjectId;

    @Column(name = "source_artifact_id", nullable = false)
    private Long sourceArtifactId;

    @Column(name = "source_artifact_version_id")
    private Long sourceArtifactVersionId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "insights_json", columnDefinition = "TEXT")
    private String insightsJson;

    @Column(name = "evidence_quotes_json", columnDefinition = "TEXT")
    private String evidenceQuotesJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_status", nullable = false, length = 32)
    private PersonalCardStatus cardStatus = PersonalCardStatus.READY;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
