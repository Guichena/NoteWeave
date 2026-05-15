ALTER TABLE document
    ADD COLUMN active_index_version INT NOT NULL DEFAULT 0 AFTER content_hash,
    ADD COLUMN parsed_text_object_key VARCHAR(512) NULL AFTER active_index_version;

CREATE TABLE document_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    index_version INT NOT NULL DEFAULT 1,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    content_hash CHAR(64),
    token_count INT NOT NULL DEFAULT 0,
    page_no INT NULL,
    section_title VARCHAR(255) NULL,
    source_start INT NULL,
    source_end INT NULL,
    embedding_id VARCHAR(128) NULL,
    es_doc_id VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_doc_chunk_index UNIQUE (document_id, index_version, chunk_index)
);

CREATE INDEX idx_document_active_version ON document (knowledge_base_id, active_index_version);
CREATE INDEX idx_document_parsed_text ON document (parsed_text_object_key);
CREATE INDEX idx_doc_chunk_doc ON document_chunk (document_id);
CREATE INDEX idx_doc_chunk_doc_version ON document_chunk (document_id, index_version);
CREATE INDEX idx_doc_chunk_kb ON document_chunk (knowledge_base_id);
CREATE INDEX idx_doc_chunk_space ON document_chunk (space_id);
