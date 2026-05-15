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
        name = "upload_chunk",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_upload_chunk", columnNames = {"upload_id", "chunk_index"})
        }
)
public class UploadChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_id", nullable = false)
    private Long uploadId;

    @Column(name = "file_md5", nullable = false, length = 32, columnDefinition = "CHAR(32)")
    private String fileMd5;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "chunk_md5", length = 32, columnDefinition = "CHAR(32)")
    private String chunkMd5;

    @Column(nullable = false)
    private Long size;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
