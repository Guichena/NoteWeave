-- Phase 18: research question layer
-- Introduces a "question" tier between research_project and chat_session so that
-- multiple conversations / multiple research questions can run in parallel
-- without context bleeding across them.

CREATE TABLE research_question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    research_project_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    question_type VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    current_hypothesis VARCHAR(2048) NULL,
    current_answer TEXT NULL,
    next_step VARCHAR(1024) NULL,
    scope_note VARCHAR(1024) NULL,
    deleted_at TIMESTAMP NULL,
    deleted_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_question_project (research_project_id),
    INDEX idx_question_space (space_id),
    INDEX idx_question_user (user_id),
    INDEX idx_question_status (status)
);

-- Bind a chat session to a research project / research question (both optional
-- for backward compatibility with existing team-chat sessions).
ALTER TABLE chat_session
    ADD COLUMN research_project_id BIGINT NULL AFTER space_id,
    ADD COLUMN research_question_id BIGINT NULL AFTER research_project_id;

CREATE INDEX idx_chat_research_project ON chat_session (research_project_id);
CREATE INDEX idx_chat_research_question ON chat_session (research_question_id);

-- Tag a session summary with the research question it belongs to, enabling the
-- question-scoped memory tier. scope_type/scope_id already carry the chat scope
-- (SPACE / KNOWLEDGE_BASE), so a dedicated column is required here.
ALTER TABLE session_summary
    ADD COLUMN research_question_id BIGINT NULL AFTER session_id;

CREATE INDEX idx_summary_research_question ON session_summary (research_question_id);
