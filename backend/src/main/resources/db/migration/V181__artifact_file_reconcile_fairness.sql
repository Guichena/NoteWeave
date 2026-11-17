alter table artifact_version add column last_reconcile_attempt_at timestamp null;

create index idx_artifact_version_reconcile
    on artifact_version(delivery_status, last_reconcile_attempt_at, created_at);
