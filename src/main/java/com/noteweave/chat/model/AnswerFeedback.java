package com.noteweave.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@Entity
@Table(
        name = "answer_feedback",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_feedback_user_message", columnNames = {"user_id", "message_id"})
        }
)
public class AnswerFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(nullable = false, length = 32)
    private String rating;

    @Column(length = 128)
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
