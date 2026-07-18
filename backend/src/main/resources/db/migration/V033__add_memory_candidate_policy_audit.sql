alter table memory_signal
    add column policy_version varchar(64) not null default 'memory-candidate-policy-v1';

alter table memory_candidate
    add column policy_version varchar(64) not null default 'memory-candidate-policy-v1';

alter table memory_candidate
    add column risk_score decimal(5,4) not null default 0.0000;

alter table memory_candidate
    add column scope_status varchar(32) not null default 'VALID';

create index idx_memory_candidate_workspace_policy
    on memory_candidate(workspace_id, policy_version, review_status, created_at);
