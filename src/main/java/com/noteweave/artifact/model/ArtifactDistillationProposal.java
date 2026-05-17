package com.noteweave.artifact.model;

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
@Table(name = "artifact_distillation_proposal")
public class ArtifactDistillationProposal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "artifact_id", nullable = false)
    private Long artifactId;

    @Column(name = "artifact_version_id", nullable = false)
    private Long artifactVersionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "research_project_id", nullable = false)
    private Long researchProjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", nullable = false, length = 32)
    private ArtifactCardType cardType;

    @Enumerated(EnumType.STRING)
    @Column(name = "proposal_status", nullable = false, length = 32)
    private ArtifactDistillationProposalStatus proposalStatus = ArtifactDistillationProposalStatus.PENDING;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "insights_json", columnDefinition = "TEXT")
    private String insightsJson;

    @Column(name = "evidence_quotes_json", columnDefinition = "TEXT")
    private String evidenceQuotesJson;

    @Column(name = "confirmed_synthesis_card_id")
    private Long confirmedSynthesisCardId;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
