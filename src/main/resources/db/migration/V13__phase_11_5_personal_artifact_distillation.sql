CREATE TABLE synthesis_card (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT NOT NULL,
    source_artifact_id BIGINT NOT NULL,
    source_artifact_version_id BIGINT,
    title VARCHAR(255) NOT NULL,
    summary TEXT,
    insights_json TEXT,
    evidence_quotes_json TEXT,
    card_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_synthesis_project ON synthesis_card (research_project_id);
CREATE INDEX idx_synthesis_artifact ON synthesis_card (source_artifact_id);
CREATE INDEX idx_synthesis_space ON synthesis_card (space_id);

CREATE TABLE synthesis_concept_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    synthesis_card_id BIGINT NOT NULL,
    concept_card_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'RELATED',
    evidence TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_synthesis_concept ON synthesis_concept_relation (synthesis_card_id, concept_card_id, relation_type);
CREATE INDEX idx_scr_synthesis ON synthesis_concept_relation (synthesis_card_id);
CREATE INDEX idx_scr_concept ON synthesis_concept_relation (concept_card_id);

CREATE TABLE artifact_card_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    artifact_version_id BIGINT,
    card_type VARCHAR(32) NOT NULL,
    card_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_artifact_card_relation ON artifact_card_relation (artifact_id, card_type, card_id, relation_type);
CREATE INDEX idx_artifact_card_relation_artifact ON artifact_card_relation (artifact_id);
CREATE INDEX idx_artifact_card_relation_card ON artifact_card_relation (card_type, card_id);

CREATE TABLE synthesis_card_citation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    synthesis_card_id BIGINT NOT NULL,
    citation_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'EVIDENCE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_synthesis_card_citation ON synthesis_card_citation (synthesis_card_id, citation_id, relation_type);
CREATE INDEX idx_synthesis_card_citation_citation ON synthesis_card_citation (citation_id);

CREATE TABLE artifact_distillation_proposal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    artifact_version_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    research_project_id BIGINT NOT NULL,
    card_type VARCHAR(32) NOT NULL,
    proposal_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    title VARCHAR(255) NOT NULL,
    summary TEXT,
    insights_json TEXT,
    evidence_quotes_json TEXT,
    confirmed_synthesis_card_id BIGINT,
    confirmed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_distill_proposal_artifact ON artifact_distillation_proposal (artifact_id);
CREATE INDEX idx_distill_proposal_version ON artifact_distillation_proposal (artifact_version_id);
CREATE INDEX idx_distill_proposal_user ON artifact_distillation_proposal (user_id);
