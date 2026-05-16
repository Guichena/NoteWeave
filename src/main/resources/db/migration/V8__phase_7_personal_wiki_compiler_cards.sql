CREATE TABLE article_card (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT,
    key_points_json TEXT,
    tags_json TEXT,
    evidence_quotes_json TEXT,
    card_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_article_source ON article_card (source_id);
CREATE INDEX idx_article_project ON article_card (research_project_id);
CREATE INDEX idx_article_space ON article_card (space_id);

CREATE TABLE concept_card (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    definition TEXT,
    explanation LONGTEXT,
    use_cases_json TEXT,
    common_misunderstandings_json TEXT,
    evidence_quotes_json TEXT,
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    card_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_project_concept_name ON concept_card (research_project_id, normalized_name);
CREATE INDEX idx_concept_project ON concept_card (research_project_id);
CREATE INDEX idx_concept_space ON concept_card (space_id);

CREATE TABLE concept_alias (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    concept_card_id BIGINT NOT NULL,
    alias VARCHAR(255) NOT NULL,
    normalized_alias VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_concept_alias ON concept_alias (concept_card_id, normalized_alias);
CREATE INDEX idx_alias_name ON concept_alias (normalized_alias);

CREATE TABLE concept_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    research_project_id BIGINT NOT NULL,
    source_concept_id BIGINT NOT NULL,
    target_concept_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    description VARCHAR(512),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_concept_relation ON concept_relation (source_concept_id, target_concept_id, relation_type);
CREATE INDEX idx_relation_project ON concept_relation (research_project_id);

CREATE TABLE article_concept_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_card_id BIGINT NOT NULL,
    concept_card_id BIGINT NOT NULL,
    source_id BIGINT,
    evidence TEXT,
    relevance_score DECIMAL(5,4) NOT NULL DEFAULT 0.0000,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_article_concept ON article_concept_relation (article_card_id, concept_card_id);
CREATE INDEX idx_acr_concept ON article_concept_relation (concept_card_id);
CREATE INDEX idx_acr_article ON article_concept_relation (article_card_id);

CREATE TABLE article_card_citation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    article_card_id BIGINT NOT NULL,
    citation_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'EVIDENCE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_article_card_citation ON article_card_citation (article_card_id, citation_id, relation_type);
CREATE INDEX idx_acc_citation ON article_card_citation (citation_id);

CREATE TABLE concept_card_citation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    concept_card_id BIGINT NOT NULL,
    citation_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'EVIDENCE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_concept_card_citation ON concept_card_citation (concept_card_id, citation_id, relation_type);
CREATE INDEX idx_ccc_citation ON concept_card_citation (citation_id);
