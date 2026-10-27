-- M-ARTIFACT-EDIT-V2（FB-04 / N-03 / RB-006）：编辑回执的两阶段独立状态机与输入可重放。
-- V200 的单 attempt_count/next_attempt_at 只能表达一个阶段，且 RUNNING 缺失导致
-- 崩溃后无法区分「未处理」与「处理中」；重试时间曾被字面值比较错误清空（N-03）。
-- 本迁移：
--   1) 阶段状态增加 RUNNING；约束整体重建；
--   2) 每阶段独立 attempts / next_attempt_at / error_code；
--   3) 一次性认领令牌 claim_token，写回必须同时持有 id+claim_token+RUNNING；
--   4) 完整有界输入快照落库（schemaVersion/payloadDigest/actingUserId/base+new version/
--      before/after digest/changedSections/diffStats/diffFragments），重放不再依赖内存消息，
--      也不再出现空差异按满幅 1.0 处理（RB-006）。
-- 旧列 attempt_count / max_attempts / next_attempt_at / lease_* 保留：max_attempts 继续
-- 作为两个阶段共享的上限来源；attempt_count/next_attempt_at 不再驱动状态机。
alter table memory_artifact_edit_receipt drop constraint ck_memory_edit_outcome;
alter table memory_artifact_edit_receipt drop constraint ck_memory_edit_candidate;

alter table memory_artifact_edit_receipt add constraint ck_memory_edit_outcome check (
    outcome_status in ('PENDING', 'RUNNING', 'APPLIED', 'SKIPPED', 'RETRY_WAIT', 'TERMINAL_FAILURE')
);
alter table memory_artifact_edit_receipt add constraint ck_memory_edit_candidate check (
    candidate_status in ('PENDING', 'RUNNING', 'APPLIED', 'SKIPPED', 'RETRY_WAIT', 'TERMINAL_FAILURE')
);

alter table memory_artifact_edit_receipt add column outcome_attempts int not null default 0;
alter table memory_artifact_edit_receipt add column outcome_next_attempt_at timestamp null;
alter table memory_artifact_edit_receipt add column outcome_error_code varchar(128) null;
alter table memory_artifact_edit_receipt add column candidate_attempts int not null default 0;
alter table memory_artifact_edit_receipt add column candidate_next_attempt_at timestamp null;
alter table memory_artifact_edit_receipt add column candidate_error_code varchar(128) null;
alter table memory_artifact_edit_receipt add column claim_token varchar(64) null;

alter table memory_artifact_edit_receipt add column schema_version int not null default 1;
alter table memory_artifact_edit_receipt add column payload_digest varchar(64) null;
alter table memory_artifact_edit_receipt add column acting_user_id varchar(36) null;
alter table memory_artifact_edit_receipt add column base_version_id varchar(36) null;
alter table memory_artifact_edit_receipt add column before_digest varchar(64) null;
alter table memory_artifact_edit_receipt add column changed_sections_json varchar(4000) null;
alter table memory_artifact_edit_receipt add column diff_stats_json varchar(1000) null;
alter table memory_artifact_edit_receipt add column diff_fragments_json varchar(4000) null;

create index idx_memory_artifact_edit_stage_due
    on memory_artifact_edit_receipt(outcome_status, outcome_next_attempt_at);
create index idx_memory_artifact_edit_candidate_due
    on memory_artifact_edit_receipt(candidate_status, candidate_next_attempt_at);
