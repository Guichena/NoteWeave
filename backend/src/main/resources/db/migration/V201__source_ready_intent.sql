-- M-WIKI-INTENT：source READY 持久摄取意图（B-02 / C3）。
-- 资料索引 READY 提交的同一事务内写入，身份 = workspace/source/snapshot/ingestPolicyVersion；
-- 同一快照同政策只产生一个有效意图。旧 AFTER_COMMIT 监听仅作为唤醒提示保留，
-- 不再是唯一可靠交付；消费侧（A 的 WikiIngestHandler）按意图认领租约后建 Wiki 任务。
-- ADR：未复用 task_outbox 承载，因为 outbox 行强制 task 外键，会在 B 的 READY 事务里
-- 耦合 A 的 Wiki 任务创建；专用表让消费契约与任务创建解耦。
create table source_ready_intent (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    source_id varchar(36) not null,
    source_snapshot_id varchar(36) not null,
    ingest_policy_version varchar(64) not null,
    status varchar(32) not null,
    lease_owner varchar(128) null,
    lease_until timestamp null,
    consumed_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_source_ready_intent unique (workspace_id, source_id, source_snapshot_id, ingest_policy_version),
    constraint ck_source_ready_intent_status check (
        status in ('PENDING', 'CLAIMED', 'CONSUMED', 'IGNORED_STALE')
    )
);

create index idx_source_ready_intent_due on source_ready_intent(status, created_at);
