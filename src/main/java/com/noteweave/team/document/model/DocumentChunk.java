package com.noteweave.team.document.model;

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
        name = "document_chunk",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_doc_chunk_index", columnNames = {"document_id", "index_version", "chunk_index"})
        }
)
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "knowledge_base_id", nullable = false)
    private Long knowledgeBaseId;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "index_version", nullable = false)
    private int indexVersion;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_hash", length = 64, columnDefinition = "CHAR(64)")
    private String contentHash;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @Column(name = "page_no")
    private Integer pageNo;

    @Column(name = "section_title", length = 255)
    private String sectionTitle;

    @Column(name = "source_start")
    private Integer sourceStart;

    @Column(name = "source_end")
    private Integer sourceEnd;

    @Column(name = "embedding_id", length = 128)
    private String embeddingId;

    @Column(name = "es_doc_id", length = 128)
    private String esDocId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
