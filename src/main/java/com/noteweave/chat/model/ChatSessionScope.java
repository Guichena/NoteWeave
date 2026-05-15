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

@Getter
@Setter
@Entity
@Table(
        name = "chat_session_scope",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_session_scope", columnNames = {"session_id", "scope_type", "scope_id"})
        }
)
public class ChatSessionScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "scope_type", nullable = false, length = 32)
    private String scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
