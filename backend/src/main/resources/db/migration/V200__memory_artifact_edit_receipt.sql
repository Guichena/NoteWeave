-- M-ARTIFACT-EDIT：产物编辑事件的两阶段处理回执（B-08，消费 A 的 C4 事件）。
-- outcome（既有 Memory 反馈）与 candidate（新偏好候选）分别记录完成状态，
-- 一个阶段失败可单独重试，不重复执行另一阶段；按 workspace + editEventId 幂等。
-- 事件结构按 plan-v3 第 9.2 节：editEventId/workspaceId/artifactVersionId/baseVersionId/
-- originTaskId/beforeDigest/afterDigest/changedSections。
create table memory_artifact_edit_receipt (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    edit_event_id varchar(64) not null,
    artifact_version_id varchar(36) null,
    origin_task_id varchar(36) null,
    after_digest varchar(64) null,
    outcome_status varchar(32) not null,
    candidate_status varchar(32) not null,
    attempt_count int not null default 0,
    max_attempts int not null default 3,
    next_attempt_at timestamp null,
    lease_owner varchar(128) null,
    lease_until timestamp null,
    last_error_code varchar(128) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_memory_artifact_edit_receipt unique (workspace_id, edit_event_id),
    constraint ck_memory_edit_outcome check (
        outcome_status in ('PENDING', 'APPLIED', 'SKIPPED', 'RETRY_WAIT', 'TERMINAL_FAILURE')
    ),
    constraint ck_memory_edit_candidate check (
        candidate_status in ('PENDING', 'APPLIED', 'SKIPPED', 'RETRY_WAIT', 'TERMINAL_FAILURE')
    )
);

create index idx_memory_artifact_edit_due
    on memory_artifact_edit_receipt(next_attempt_at, outcome_status, candidate_status);
