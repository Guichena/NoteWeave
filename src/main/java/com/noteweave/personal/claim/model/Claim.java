package com.noteweave.personal.claim.model;

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
@Table(name = "claim")
public class Claim {

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

    @Column(nullable = false, length = 2048)
    private String statement;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false, length = 32)
    private ClaimType claimType = ClaimType.HYPOTHESIS;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ClaimStance stance = ClaimStance.UNCERTAIN;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence = BigDecimal.valueOf(0.5d);

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "supersedes_claim_id")
    private Long supersedesClaimId;

    @Column(name = "origin_session_id")
    private Long originSessionId;

    @Column(name = "origin_user_message_id")
    private Long originUserMessageId;

    @Column(name = "origin_assistant_message_id")
    private Long originAssistantMessageId;

    @Column(name = "writeback_key", length = 64, columnDefinition = "CHAR(64)")
    private String writebackKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_status", nullable = false, length = 32)
    private PersonalCardStatus cardStatus = PersonalCardStatus.READY;

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
