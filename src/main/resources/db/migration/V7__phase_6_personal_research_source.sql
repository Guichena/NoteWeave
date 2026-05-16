CREATE TABLE research_project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(1024),
    research_goal VARCHAR(1024),
    compile_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    deleted_at TIMESTAMP NULL,
    deleted_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_project_space ON research_project (space_id);
CREATE INDEX idx_project_user ON research_project (user_id);
CREATE INDEX idx_project_status ON research_project (status);

CREATE TABLE source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    url VARCHAR(1024),
    object_key VARCHAR(512),
    raw_text_object_key VARCHAR(512),
    parsed_text_object_key VARCHAR(512),
    content_hash CHAR(64),
    import_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    compile_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    token_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_by BIGINT NOT NULL,
    deleted_at TIMESTAMP NULL,
    deleted_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_source_project ON source (research_project_id);
CREATE INDEX idx_source_space ON source (space_id);
CREATE INDEX idx_source_import_status ON source (import_status);
CREATE INDEX idx_source_compile_status ON source (compile_status);
CREATE INDEX idx_source_hash ON source (content_hash);
