-- Phase 21: claim writeback support
-- Tracks which chat round produced a candidate claim so repeated writeback runs
-- do not duplicate the same extracted judgment.

ALTER TABLE claim
    ADD COLUMN origin_session_id BIGINT NULL AFTER supersedes_claim_id,
    ADD COLUMN origin_user_message_id BIGINT NULL AFTER origin_session_id,
    ADD COLUMN origin_assistant_message_id BIGINT NULL AFTER origin_user_message_id,
    ADD COLUMN writeback_key CHAR(64) NULL AFTER origin_assistant_message_id;

CREATE INDEX idx_claim_writeback_key ON claim (writeback_key);
CREATE INDEX idx_claim_origin_session ON claim (origin_session_id);
