package com.noteweave.llm.model;

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

@Getter
@Setter
@Entity
@Table(name = "llm_call_log")
public class LlmCallLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(name = "prompt_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String promptHash;

    @Column(name = "input_tokens", nullable = false)
    private Integer inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private Integer outputTokens;

    @Column(name = "latency_ms", nullable = false)
    private Long latencyMs;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
