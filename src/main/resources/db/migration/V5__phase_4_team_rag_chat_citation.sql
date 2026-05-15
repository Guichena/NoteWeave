CREATE TABLE chat_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    session_type VARCHAR(32) NOT NULL,
    session_kind VARCHAR(32) NOT NULL DEFAULT 'FORMAL',
    title VARCHAR(255) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_ids_snapshot_json TEXT NULL,
    summary TEXT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    runtime_status VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    latest_context_snapshot_json TEXT NULL,
    last_active_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_chat_user_space (user_id, space_id),
    INDEX idx_chat_space_type (space_id, session_type),
    INDEX idx_chat_status (status),
    INDEX idx_chat_runtime_status (runtime_status)
);

CREATE TABLE chat_session_scope (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_session_scope UNIQUE (session_id, scope_type, scope_id),
    INDEX idx_scope_ref (scope_type, scope_id)
);

CREATE TABLE chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    message_seq INT NOT NULL,
    role VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    artifact_id BIGINT NULL,
    request_id VARCHAR(64) NULL,
    token_usage_json TEXT NULL,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_message_seq UNIQUE (session_id, message_seq),
    INDEX idx_message_session (session_id),
    INDEX idx_message_role (session_id, role)
);

CREATE TABLE citation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    chunk_id BIGINT NULL,
    page_no INT NULL,
    start_offset INT NULL,
    end_offset INT NULL,
    title VARCHAR(255) NULL,
    quote_text TEXT NULL,
    quote_hash CHAR(64) NULL,
    location_info VARCHAR(255) NULL,
    snapshot_object_key VARCHAR(512) NULL,
    source_version VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_citation_space (space_id),
    INDEX idx_citation_source (source_type, source_id),
    INDEX idx_citation_chunk (chunk_id),
    INDEX idx_citation_quote_hash (quote_hash)
);

CREATE TABLE message_citation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    citation_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_message_citation UNIQUE (message_id, citation_id),
    INDEX idx_mc_citation (citation_id)
);

CREATE TABLE retrieval_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    query_text TEXT NOT NULL,
    top_k INT NOT NULL,
    latency_ms BIGINT NOT NULL,
    retrieved_chunk_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    INDEX idx_rt_session_message (session_id, message_id),
    INDEX idx_rt_space_created (space_id, created_at)
);

CREATE TABLE llm_call_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    message_id BIGINT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_hash CHAR(64) NOT NULL,
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    latency_ms BIGINT NOT NULL,
    success BIT NOT NULL DEFAULT b'0',
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL,
    INDEX idx_llm_session_message (session_id, message_id),
    INDEX idx_llm_space_created (space_id, created_at)
);

CREATE TABLE answer_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    rating VARCHAR(32) NOT NULL,
    reason VARCHAR(128) NULL,
    comment TEXT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_feedback_user_message UNIQUE (user_id, message_id),
    INDEX idx_feedback_session_message (session_id, message_id),
    INDEX idx_feedback_space_created (space_id, created_at)
);
