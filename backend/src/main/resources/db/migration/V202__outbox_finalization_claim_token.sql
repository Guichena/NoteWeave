-- M-FINALIZE-FENCE（FB-01）：补偿认领令牌。
-- 每次认领生成新的 claim_token，finish/retry/terminal 写回必须同时持有
-- id + claim_token + RUNNING 状态；同 owner 的上一次迟到写回因令牌不同被拒绝，
-- 租约 fencing（本表）与业务代次 fencing（task_outbox / TASK_REDRIVEN 事件）同时生效。
-- instanceId（lease_owner）保留用于诊断，不再充当写回凭证。
alter table outbox_finalization add column claim_token varchar(64) null;

-- 人工重驱同事务失效旧代次补偿后的可观测性：按代次与业务任务定位。
create index idx_outbox_finalization_generation
    on outbox_finalization(store_type, outbox_id, delivery_generation, status);
