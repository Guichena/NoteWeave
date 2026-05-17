CREATE TABLE session_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    topic VARCHAR(128) NULL,
    query_type VARCHAR(64) NULL,
    scope_type VARCHAR(32) NULL,
    scope_id BIGINT NULL,
    summary TEXT NOT NULL,
    resolved_entities_json TEXT NULL,
    reference_source_json TEXT NULL,
    importance_score DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    confidence_score DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    stale BIT NOT NULL DEFAULT b'0',
    pin BIT NOT NULL DEFAULT b'0',
    expires_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_summary_user_space (user_id, space_id),
    INDEX idx_summary_session (session_id),
    INDEX idx_summary_scope (scope_type, scope_id),
    INDEX idx_summary_expiry (expires_at)
);

CREATE TABLE space_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    topic VARCHAR(128) NULL,
    summary TEXT NULL,
    focused_sources_json TEXT NULL,
    resolved_entities_json TEXT NULL,
    artifact_preferences_json TEXT NULL,
    conversation_patterns_json TEXT NULL,
    expires_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_space_memory_user_space (user_id, space_id),
    INDEX idx_space_memory_updated (updated_at)
);

CREATE TABLE user_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    summary TEXT NULL,
    preferences_json TEXT NULL,
    style_profile_json TEXT NULL,
    habit_profile_json TEXT NULL,
    memory_write_enabled BIT NOT NULL DEFAULT b'1',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_user_memory_user (user_id),
    INDEX idx_user_memory_updated (updated_at)
);

CREATE TABLE memory_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NULL,
    memory_type VARCHAR(32) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    summary TEXT NOT NULL,
    source_type VARCHAR(64) NULL,
    source_id BIGINT NULL,
    importance_score DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    confidence_score DECIMAL(5,4) NOT NULL DEFAULT 0.5000,
    pin BIT NOT NULL DEFAULT b'0',
    expires_at DATETIME NULL,
    deleted_at DATETIME NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    INDEX idx_memory_item_scope (user_id, space_id),
    INDEX idx_memory_item_active (user_id, space_id, deleted_at, expires_at),
    INDEX idx_memory_item_topic (user_id, topic),
    INDEX idx_memory_item_source (source_type, source_id)
);
