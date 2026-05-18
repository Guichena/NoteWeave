CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id BIGINT NOT NULL,
    space_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NULL,
    target_id BIGINT NULL,
    request_id VARCHAR(128) NULL,
    ip_address VARCHAR(128) NULL,
    user_agent VARCHAR(512) NULL,
    before_json LONGTEXT NULL,
    after_json LONGTEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_operator_created (operator_id, created_at),
    INDEX idx_audit_action_created (action, created_at),
    INDEX idx_audit_target (target_type, target_id)
);

CREATE TABLE ops_cleanup_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    target_type VARCHAR(64) NULL,
    target_id BIGINT NULL,
    scan_count INT NOT NULL DEFAULT 0,
    cleanup_count INT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    started_by BIGINT NOT NULL,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ops_cleanup_job_type_created (job_type, created_at),
    INDEX idx_ops_cleanup_job_status_created (status, created_at)
);

CREATE TABLE ops_cleanup_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    target_type VARCHAR(64) NULL,
    target_id BIGINT NULL,
    object_key VARCHAR(512) NULL,
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_ops_cleanup_item_job_status (job_id, status),
    INDEX idx_ops_cleanup_item_target (target_type, target_id)
);

CREATE TABLE system_health_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    component VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    latency_ms BIGINT NULL,
    detail_json LONGTEXT NULL,
    checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_health_component_checked (component, checked_at)
);
