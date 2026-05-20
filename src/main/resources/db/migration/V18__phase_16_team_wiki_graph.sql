CREATE TABLE wiki_page_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    source_page_id BIGINT NOT NULL,
    target_page_id BIGINT NULL,
    target_title VARCHAR(255) NOT NULL,
    relation_status VARCHAR(32) NOT NULL DEFAULT 'RESOLVED',
    mention_count INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
)
;

CREATE UNIQUE INDEX uk_wiki_page_link_source_target_title ON wiki_page_link (source_page_id, target_title);
CREATE INDEX idx_wiki_page_link_space ON wiki_page_link (space_id);
CREATE INDEX idx_wiki_page_link_source ON wiki_page_link (source_page_id);
CREATE INDEX idx_wiki_page_link_target ON wiki_page_link (target_page_id);
CREATE INDEX idx_wiki_page_link_status ON wiki_page_link (relation_status);
