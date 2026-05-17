CREATE TABLE methodology_card (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT NULL,
    name VARCHAR(255) NOT NULL,
    scene VARCHAR(255),
    problem_type VARCHAR(128),
    workflow_json LONGTEXT,
    required_concepts_json LONGTEXT,
    output_structure_json LONGTEXT,
    quality_checklist_json LONGTEXT,
    card_source VARCHAR(32) NOT NULL DEFAULT 'PRESET',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_methodology_card_scope_name_source
    ON methodology_card (space_id, name, card_source);
CREATE INDEX idx_methodology_project ON methodology_card (research_project_id);
CREATE INDEX idx_methodology_space ON methodology_card (space_id);
CREATE INDEX idx_methodology_problem_type ON methodology_card (problem_type);
