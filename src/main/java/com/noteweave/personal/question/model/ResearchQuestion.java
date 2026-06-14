package com.noteweave.personal.question.model;

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
@Table(name = "research_question")
public class ResearchQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "research_project_id", nullable = false)
    private Long researchProjectId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "question_type", length = 64)
    private String questionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ResearchQuestionStatus status = ResearchQuestionStatus.OPEN;

    @Column(name = "current_hypothesis", length = 2048)
    private String currentHypothesis;

    @Column(name = "current_answer", columnDefinition = "TEXT")
    private String currentAnswer;

    @Column(name = "next_step", length = 1024)
    private String nextStep;

    @Column(name = "scope_note", length = 1024)
    private String scopeNote;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
