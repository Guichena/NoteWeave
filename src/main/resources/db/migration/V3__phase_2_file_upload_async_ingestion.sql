CREATE TABLE knowledge_base (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    deleted_at TIMESTAMP NULL,
    deleted_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_kb_space_status ON knowledge_base (space_id, status);
CREATE INDEX idx_kb_space_created ON knowledge_base (space_id, created_at);

CREATE TABLE document_upload (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NULL,
    user_id BIGINT NOT NULL,
    file_md5 CHAR(32) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128),
    total_size BIGINT NOT NULL,
    chunk_size INT NOT NULL,
    total_chunks INT NOT NULL,
    object_key VARCHAR(512),
    task_id BIGINT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'INIT',
    error_message TEXT,
    expires_at TIMESTAMP NULL,
    cancelled_at TIMESTAMP NULL,
    merged_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_upload_kb_status ON document_upload (knowledge_base_id, status);
CREATE INDEX idx_upload_space_user ON document_upload (space_id, user_id);
CREATE INDEX idx_upload_expires_status ON document_upload (expires_at, status);
CREATE INDEX idx_upload_doc ON document_upload (document_id);
CREATE INDEX idx_upload_task ON document_upload (task_id);

CREATE TABLE upload_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    upload_id BIGINT NOT NULL,
    file_md5 CHAR(32) NOT NULL,
    chunk_index INT NOT NULL,
    chunk_md5 CHAR(32),
    size BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_upload_chunk UNIQUE (upload_id, chunk_index)
)
;

CREATE INDEX idx_chunk_upload ON upload_chunk (upload_id, chunk_index);

CREATE TABLE file_object (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    size BIGINT NOT NULL,
    content_type VARCHAR(128),
    ref_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_file_object_space_hash UNIQUE (space_id, content_hash)
)
;

CREATE INDEX idx_file_object_space_status ON file_object (space_id, status);

CREATE TABLE document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    file_object_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    original_filename VARCHAR(255),
    content_hash CHAR(64) NOT NULL,
    parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_PROCESS',
    token_count INT NOT NULL DEFAULT 0,
    chunk_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_by BIGINT NOT NULL,
    deleted_at TIMESTAMP NULL,
    deleted_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_document_kb_status ON document (knowledge_base_id, status);
CREATE INDEX idx_document_space_status ON document (space_id, status);
CREATE INDEX idx_document_hash ON document (space_id, content_hash);
