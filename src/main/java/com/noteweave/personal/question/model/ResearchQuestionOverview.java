package com.noteweave.personal.question.model;

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
@Table(name = "research_question_overview")
public class ResearchQuestionOverview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "research_project_id", nullable = false)
    private Long researchProjectId;

    @Column(name = "research_question_id", nullable = false)
    private Long researchQuestionId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "current_answer", columnDefinition = "TEXT")
    private String currentAnswer;

    @Column(name = "key_claims_json", columnDefinition = "LONGTEXT")
    private String keyClaimsJson;

    @Column(name = "supporting_evidence_json", columnDefinition = "LONGTEXT")
    private String supportingEvidenceJson;

    @Column(name = "conflicts_json", columnDefinition = "LONGTEXT")
    private String conflictsJson;

    @Column(name = "open_issues_json", columnDefinition = "LONGTEXT")
    private String openIssuesJson;

    @Column(name = "next_steps_json", columnDefinition = "LONGTEXT")
    private String nextStepsJson;

    @Column(columnDefinition = "LONGTEXT")
    private String markdown;

    @Column(name = "generated_from_snapshot_json", columnDefinition = "LONGTEXT")
    private String generatedFromSnapshotJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
