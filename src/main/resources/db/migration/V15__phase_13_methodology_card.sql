ALTER TABLE methodology_card
    ADD COLUMN card_scope VARCHAR(32) NOT NULL DEFAULT 'SYSTEM' AFTER card_source,
    ADD COLUMN created_by BIGINT NULL AFTER version;

UPDATE methodology_card
SET card_scope = CASE
    WHEN card_source = 'PRESET' THEN 'SYSTEM'
    WHEN research_project_id IS NULL THEN 'SPACE'
    ELSE 'PROJECT'
END
WHERE card_scope IS NULL OR card_scope = 'SYSTEM';

DROP INDEX uk_methodology_card_scope_name_source ON methodology_card;

CREATE INDEX idx_methodology_scope_status ON methodology_card (space_id, card_scope, status);
CREATE INDEX idx_methodology_scope_project_status ON methodology_card (space_id, research_project_id, card_scope, status);
