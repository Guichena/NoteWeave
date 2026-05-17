package com.noteweave.personal.card.model;

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
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@Entity
@Table(name = "synthesis_concept_relation")
public class SynthesisConceptRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "synthesis_card_id", nullable = false)
    private Long synthesisCardId;

    @Column(name = "concept_card_id", nullable = false)
    private Long conceptCardId;

    @Column(name = "relation_type", nullable = false, length = 32)
    private String relationType = "RELATED";

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
