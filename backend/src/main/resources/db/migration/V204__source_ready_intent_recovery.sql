-- M-WIKI-INTENT-RECOVERY（FB-02 / N-06）：READY 意图的有界恢复。
-- claimDue 的 created_at >= now - maxAge 过滤会让停机超过 maxAge 的未决意图永久滞留；
-- 本迁移移除该语义（代码侧）并补齐恢复字段：
--   1) 认领令牌 claim_token：回写按 id+claim_token CAS，迟到写回被拒绝；
--   2) attempt_count / next_attempt_at / last_error_code：暂时失败退避重试，
--      达上限进入 FAILED 终态（毒性意图不阻塞后续），都有界可观测；
--   3) 状态增加 RETRY_WAIT / FAILED；快照被取代/来源删除仍走 IGNORED_STALE。
-- 旧行初始化即兼容：attempt_count=0、next_attempt_at=null 表示立即可领取，
-- 停机积压的 PENDING/CLAIMED 行在调度器接通后按 created_at 顺序恢复。
alter table source_ready_intent drop constraint ck_source_ready_intent_status;

alter table source_ready_intent add constraint ck_source_ready_intent check (
    status in ('PENDING', 'CLAIMED', 'RETRY_WAIT', 'CONSUMED', 'IGNORED_STALE', 'FAILED')
);

alter table source_ready_intent add column attempt_count int not null default 0;
alter table source_ready_intent add column max_attempts int not null default 5;
alter table source_ready_intent add column next_attempt_at timestamp null;
alter table source_ready_intent add column claim_token varchar(64) null;
alter table source_ready_intent add column last_error_code varchar(128) null;
alter table source_ready_intent add column last_attempt_at timestamp null;

create index idx_source_ready_intent_claim
    on source_ready_intent(status, next_attempt_at, lease_until, created_at);
