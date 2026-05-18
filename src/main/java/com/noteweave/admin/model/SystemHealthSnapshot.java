package com.noteweave.admin.model;

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

@Getter
@Setter
@Entity
@Table(name = "system_health_snapshot")
public class SystemHealthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private SystemHealthComponent component;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SystemHealthStatus status;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "detail_json", columnDefinition = "LONGTEXT")
    private String detailJson;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;
}
