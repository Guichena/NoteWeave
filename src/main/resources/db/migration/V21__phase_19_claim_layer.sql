-- Phase 19: claim layer
-- A Claim is a question-scoped judgment (hypothesis / conclusion / open issue / decision).
-- Concepts and entities can be shared across questions, but a *conclusion* must always be
-- anchored to a single research question so the same concept can play different roles in
-- different questions without the conclusions bleeding together.

CREATE TABLE claim (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    research_project_id BIGINT NOT NULL,
    research_question_id BIGINT NOT NULL,
    statement VARCHAR(2048) NOT NULL,
    claim_type VARCHAR(32) NOT NULL DEFAULT 'HYPOTHESIS',
    stance VARCHAR(32) NOT NULL DEFAULT 'UNCERTAIN',
    confidence DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    rationale TEXT NULL,
    supersedes_claim_id BIGINT NULL,
    card_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    deleted_at TIMESTAMP NULL,
    deleted_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_claim_question (research_question_id),
    INDEX idx_claim_project (research_project_id),
    INDEX idx_claim_space (space_id),
    INDEX idx_claim_supersedes (supersedes_claim_id)
);

-- Bridge: a claim references one or more concept cards, with a typed relation so we can
-- express SUPPORTS / CONTRADICTS / RELATED. This is how a shared concept ends up carrying
-- different (even conflicting) conclusions across questions.
CREATE TABLE claim_concept_relation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT NOT NULL,
    concept_card_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'RELATED',
    evidence TEXT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_claim_concept UNIQUE (claim_id, concept_card_id, relation_type),
    INDEX idx_claim_concept_claim (claim_id),
    INDEX idx_claim_concept_concept (concept_card_id)
);

-- Bridge: the evidence that grounds a claim, mirroring synthesis_card_citation.
CREATE TABLE claim_citation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_id BIGINT NOT NULL,
    citation_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'EVIDENCE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_claim_citation UNIQUE (claim_id, citation_id),
    INDEX idx_claim_citation_claim (claim_id)
);
