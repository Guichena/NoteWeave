CREATE TABLE task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    space_id BIGINT NOT NULL,
    research_project_id BIGINT,
    task_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    target_id BIGINT,
    task_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(255) NOT NULL,
    input_json TEXT,
    output_json TEXT,
    error_message TEXT,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry_count INT NOT NULL DEFAULT 3,
    result_ref_type VARCHAR(64),
    result_ref_id BIGINT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_task_idempotency UNIQUE (idempotency_key)
)
;

CREATE INDEX idx_task_space_status ON task (space_id, task_status);
CREATE INDEX idx_task_type_status ON task (task_type, task_status);
CREATE INDEX idx_task_target ON task (target_type, target_id);

CREATE TABLE task_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    worker_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    error_code VARCHAR(64),
    error_message TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_task_attempt_no UNIQUE (task_id, attempt_no)
)
;

CREATE INDEX idx_task_attempt_task ON task_attempt (task_id, attempt_no);

CREATE TABLE task_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    message VARCHAR(512),
    payload_json TEXT,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL
)
;

CREATE INDEX idx_task_event_task_created ON task_event (task_id, created_at, id);

CREATE TABLE task_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP,
    sent_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_task_outbox_idem UNIQUE (idempotency_key)
)
;

CREATE INDEX idx_task_outbox_status_retry ON task_outbox (status, next_retry_at, created_at);
