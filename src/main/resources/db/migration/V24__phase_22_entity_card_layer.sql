-- Phase 22: entity card layer
-- Adds concrete entity anchors so concepts / claims can point to stable real-world objects.

CREATE TABLE entity_card (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT NULL,
    canonical_name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    aliases_json TEXT NULL,
    description TEXT NULL,
    external_refs_json TEXT NULL,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    card_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_entity_card_space (space_id),
    INDEX idx_entity_card_project (research_project_id),
    INDEX idx_entity_card_normalized (normalized_name)
);

CREATE TABLE concept_entity_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    concept_card_id BIGINT NOT NULL,
    entity_card_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'RELATED',
    evidence TEXT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_concept_entity UNIQUE (concept_card_id, entity_card_id, relation_type),
    INDEX idx_concept_entity_concept (concept_card_id),
    INDEX idx_concept_entity_entity (entity_card_id)
);

CREATE TABLE claim_entity_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT NOT NULL,
    entity_card_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'RELATED',
    evidence TEXT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_claim_entity UNIQUE (claim_id, entity_card_id, relation_type),
    INDEX idx_claim_entity_claim (claim_id),
    INDEX idx_claim_entity_entity (entity_card_id)
);
