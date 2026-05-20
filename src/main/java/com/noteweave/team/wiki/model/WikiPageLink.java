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
@Table(name = "wiki_page_link")
public class WikiPageLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "source_page_id", nullable = false)
    private Long sourcePageId;

    @Column(name = "target_page_id")
    private Long targetPageId;

    @Column(name = "target_title", nullable = false, length = 255)
    private String targetTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_status", nullable = false, length = 32)
    private WikiPageLinkStatus relationStatus = WikiPageLinkStatus.RESOLVED;

    @Column(name = "mention_count", nullable = false)
    private Integer mentionCount = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
