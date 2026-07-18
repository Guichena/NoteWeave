alter table memory_object
    add column utility_score decimal(5,4) not null default 0.5000;

alter table memory_object
    add column application_count int not null default 0;

alter table memory_object
    add column positive_outcome_count int not null default 0;

alter table memory_object
    add column negative_outcome_count int not null default 0;

alter table memory_object
    add column edit_outcome_count int not null default 0;

alter table memory_object
    add column retry_outcome_count int not null default 0;

alter table memory_object
    add column review_status varchar(32) not null default 'APPROVED';

alter table memory_object
    add column outcome_policy_version varchar(64) not null default 'memory-outcome-policy-v1';

alter table memory_object
    add column last_outcome_at timestamp null;

alter table memory_usage_log
    add column memory_version_id varchar(36) null;

alter table memory_usage_log
    add column outcome_type varchar(32) null;

alter table memory_usage_log
    add column outcome_score decimal(5,4) null;

alter table memory_usage_log
    add column edit_magnitude decimal(5,4) null;

alter table memory_usage_log
    add column feedback_note varchar(500) null;

alter table memory_usage_log
    add column outcome_policy_version varchar(64) not null default 'memory-outcome-policy-v1';

alter table memory_usage_log
    add column outcome_at timestamp null;

create index idx_memory_usage_workspace_outcome
    on memory_usage_log(workspace_id, target_type, target_id, outcome_type, used_at);

create index idx_memory_object_workspace_review
    on memory_object(workspace_id, status, review_status, utility_score, updated_at);
