package com.noteweave.team.wiki.model;

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
        name = "wiki_page_citation",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_wiki_page_citation", columnNames = {"wiki_page_id", "wiki_page_version_id", "citation_id", "relation_type"})
        }
)
public class WikiPageCitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wiki_page_id", nullable = false)
    private Long wikiPageId;

    @Column(name = "wiki_page_version_id")
    private Long wikiPageVersionId;

    @Column(name = "citation_id", nullable = false)
    private Long citationId;

    @Column(name = "relation_type", nullable = false, length = 32)
    private String relationType = "EVIDENCE";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
