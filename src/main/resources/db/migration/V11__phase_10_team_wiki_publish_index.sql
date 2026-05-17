CREATE TABLE wiki_page (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    source_artifact_id BIGINT,
    source_message_id BIGINT,
    published_version_id BIGINT,
    index_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_by BIGINT NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMP NULL,
    deleted_by BIGINT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_wiki_page_space ON wiki_page (space_id);
CREATE INDEX idx_wiki_page_status ON wiki_page (status);
CREATE INDEX idx_wiki_page_source_artifact ON wiki_page (source_artifact_id);
CREATE INDEX idx_wiki_page_source_message ON wiki_page (source_message_id);
CREATE INDEX idx_wiki_page_index_status ON wiki_page (index_status);

CREATE TABLE wiki_page_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    wiki_page_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    content LONGTEXT NOT NULL,
    change_note VARCHAR(512),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_wiki_page_version_no ON wiki_page_version (wiki_page_id, version_no);
CREATE INDEX idx_wiki_page_version_page ON wiki_page_version (wiki_page_id);

CREATE TABLE wiki_page_citation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    wiki_page_id BIGINT NOT NULL,
    wiki_page_version_id BIGINT,
    citation_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'EVIDENCE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_wiki_page_citation ON wiki_page_citation (wiki_page_id, wiki_page_version_id, citation_id, relation_type);
CREATE INDEX idx_wiki_page_citation_citation ON wiki_page_citation (citation_id);
