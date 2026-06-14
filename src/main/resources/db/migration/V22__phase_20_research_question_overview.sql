-- Phase 20: research question overview
-- Persists a generated markdown overview for a single research question so the
-- structured claim/concept/citation graph can be consumed as a stable page.

CREATE TABLE research_question_overview (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    research_project_id BIGINT NOT NULL,
    research_question_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT NULL,
    current_answer TEXT NULL,
    key_claims_json LONGTEXT NULL,
    supporting_evidence_json LONGTEXT NULL,
    conflicts_json LONGTEXT NULL,
    open_issues_json LONGTEXT NULL,
    next_steps_json LONGTEXT NULL,
    markdown LONGTEXT NULL,
    generated_from_snapshot_json LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_question_overview_question (research_question_id),
    INDEX idx_question_overview_project (research_project_id),
    INDEX idx_question_overview_space (space_id),
    INDEX idx_question_overview_user (user_id)
);
