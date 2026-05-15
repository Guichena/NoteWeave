package com.noteweave.chat.model;

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
@Table(name = "chat_session")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false, length = 32)
    private ChatSessionType sessionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_kind", nullable = false, length = 32)
    private ChatSessionKind sessionKind = ChatSessionKind.FORMAL;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 32)
    private ChatScopeType scopeType;

    @Column(name = "scope_ids_snapshot_json", columnDefinition = "TEXT")
    private String scopeIdsSnapshotJson;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChatSessionStatus status = ChatSessionStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "runtime_status", nullable = false, length = 32)
    private ChatRuntimeStatus runtimeStatus = ChatRuntimeStatus.IDLE;

    @Column(name = "latest_context_snapshot_json", columnDefinition = "TEXT")
    private String latestContextSnapshotJson;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
