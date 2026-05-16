CREATE TABLE artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT,
    created_from_session_id BIGINT,
    created_from_message_id BIGINT,
    task_id BIGINT,
    artifact_type VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT,
    source_scope_type VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'GENERATING',
    deleted_at TIMESTAMP NULL,
    deleted_by BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_artifact_space ON artifact (space_id);
CREATE INDEX idx_artifact_project ON artifact (research_project_id);
CREATE INDEX idx_artifact_session ON artifact (created_from_session_id);
CREATE INDEX idx_artifact_type ON artifact (artifact_type);
CREATE INDEX idx_artifact_status ON artifact (status);

CREATE TABLE artifact_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    task_id BIGINT,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT,
    change_note VARCHAR(512),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_artifact_version_no ON artifact_version (artifact_id, version_no);
CREATE INDEX idx_artifact_version_task ON artifact_version (task_id);

CREATE TABLE artifact_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_artifact_source ON artifact_source (artifact_id, source_type, source_id);
CREATE INDEX idx_artifact_source_ref ON artifact_source (source_type, source_id);

CREATE TABLE artifact_citation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT NOT NULL,
    citation_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_artifact_citation ON artifact_citation (artifact_id, citation_id);
CREATE INDEX idx_ac_citation ON artifact_citation (citation_id);

CREATE TABLE session_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    artifact_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_session_artifact_relation ON session_artifact (session_id, artifact_id, relation_type);
CREATE INDEX idx_sa_artifact ON session_artifact (artifact_id);

CREATE TABLE skill_execution_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    artifact_id BIGINT,
    artifact_version_id BIGINT,
    skill_name VARCHAR(128) NOT NULL,
    input_json TEXT,
    output_json TEXT,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    latency_ms BIGINT,
    model_name VARCHAR(128),
    prompt_version VARCHAR(64),
    input_tokens INT,
    output_tokens INT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_skill_task ON skill_execution_log (task_id);
CREATE INDEX idx_skill_name ON skill_execution_log (skill_name);
