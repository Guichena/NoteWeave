package com.noteweave.team.wiki.model;

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
@Table(name = "wiki_page")
public class WikiPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WikiPageStatus status = WikiPageStatus.DRAFT;

    @Column(name = "source_artifact_id")
    private Long sourceArtifactId;

    @Column(name = "source_message_id")
    private Long sourceMessageId;

    @Column(name = "source_document_id")
    private Long sourceDocumentId;

    @Column(name = "source_personal_source_id")
    private Long sourcePersonalSourceId;

    @Column(name = "auto_maintained", nullable = false)
    private boolean autoMaintained;

    @Column(name = "source_fingerprint", length = 128)
    private String sourceFingerprint;

    @Column(name = "published_version_id")
    private Long publishedVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "index_status", nullable = false, length = 32)
    private WikiIndexStatus indexStatus = WikiIndexStatus.PENDING;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

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
