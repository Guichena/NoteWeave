ALTER TABLE wiki_page
    ADD COLUMN source_document_id BIGINT NULL AFTER source_message_id,
    ADD COLUMN source_personal_source_id BIGINT NULL AFTER source_document_id,
    ADD COLUMN auto_maintained BOOLEAN NOT NULL DEFAULT FALSE AFTER source_personal_source_id,
    ADD COLUMN source_fingerprint VARCHAR(128) NULL AFTER auto_maintained;

CREATE INDEX idx_wiki_page_source_document ON wiki_page (source_document_id);
CREATE INDEX idx_wiki_page_source_personal_source ON wiki_page (source_personal_source_id);
CREATE INDEX idx_wiki_page_auto_source_document ON wiki_page (space_id, auto_maintained, source_document_id);
CREATE INDEX idx_wiki_page_auto_source_personal ON wiki_page (space_id, auto_maintained, source_personal_source_id);
