ALTER TABLE chat_session
    ADD COLUMN draft_status VARCHAR(32) NULL AFTER runtime_status;

UPDATE chat_session
SET draft_status = 'DRAFT_ACTIVE'
WHERE session_kind = 'DRAFT' AND draft_status IS NULL;

ALTER TABLE chat_message
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED' AFTER message_type;
