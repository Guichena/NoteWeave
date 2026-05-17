package com.noteweave.personal.methodology.model;

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
@Table(name = "methodology_card")
public class MethodologyCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "research_project_id")
    private Long researchProjectId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String scene;

    @Column(name = "problem_type", length = 128)
    private String problemType;

    @Column(name = "workflow_json", columnDefinition = "LONGTEXT")
    private String workflowJson;

    @Column(name = "required_concepts_json", columnDefinition = "LONGTEXT")
    private String requiredConceptsJson;

    @Column(name = "output_structure_json", columnDefinition = "LONGTEXT")
    private String outputStructureJson;

    @Column(name = "quality_checklist_json", columnDefinition = "LONGTEXT")
    private String qualityChecklistJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_source", nullable = false, length = 32)
    private MethodologyCardSource cardSource = MethodologyCardSource.PRESET;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MethodologyCardStatus status = MethodologyCardStatus.ACTIVE;

    @Column(nullable = false)
    private Integer version = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
