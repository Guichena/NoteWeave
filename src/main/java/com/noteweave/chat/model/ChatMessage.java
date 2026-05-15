package com.noteweave.chat.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
        name = "chat_message",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_message_seq", columnNames = {"session_id", "message_seq"})
        }
)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "message_seq", nullable = false)
    private Integer messageSeq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChatMessageRole role;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 32)
    private ChatMessageType messageType = ChatMessageType.TEXT;

    @Column(name = "artifact_id")
    private Long artifactId;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "token_usage_json", columnDefinition = "TEXT")
    private String tokenUsageJson;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
