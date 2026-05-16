package com.noteweave.personal.source.model;

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
@Table(name = "source")
public class Source {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "research_project_id", nullable = false)
    private Long researchProjectId;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private SourceType sourceType;

    @Column(length = 1024)
    private String url;

    @Column(name = "object_key", length = 512)
    private String objectKey;

    @Column(name = "raw_text_object_key", length = 512)
    private String rawTextObjectKey;

    @Column(name = "parsed_text_object_key", length = 512)
    private String parsedTextObjectKey;

    @Column(name = "content_hash", length = 64, columnDefinition = "CHAR(64)")
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_status", nullable = false, length = 32)
    private SourceImportStatus importStatus = SourceImportStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "compile_status", nullable = false, length = 32)
    private SourceCompileStatus compileStatus = SourceCompileStatus.PENDING;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

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
