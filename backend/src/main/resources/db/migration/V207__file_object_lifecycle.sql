-- M-FILE-LIFECYCLE（FB-06 / RA-P2-009/011 / RB-008）：共享文件对象生命周期与派生对象登记。
-- 保留 V003 的 unique(workspace_id, sha256)：同 hash 并发插入由唯一约束选赢家，
-- 输家走独立暂存回收，不再无声复用。
-- 状态机：ACTIVE（可认领引用）→ DELETE_CLAIMED（ref=0，删除票据已绑定旧
-- key/generation，禁止新增引用）→ DELETED（旧对象已物理删除；同 hash 新上传
-- 以全新不可变 key 刷新代次重新发布，绝不复用旧 key）。
-- 旧行初始化即兼容：存量行默认 ACTIVE / generation=1。
alter table file_object add column status varchar(32) not null default 'ACTIVE';
alter table file_object add column object_generation bigint not null default 1;

alter table file_object add constraint ck_file_object_status check (
    status in ('ACTIVE', 'DELETE_CLAIMED', 'DELETED')
);

-- 删除票据：物理 IO 在事务外执行，票据绑定删除认领时的不可变 key 与代次；
-- 过期清理记录不能使用该行后来更新的新 key。
create table file_object_delete_ticket (
    id varchar(36) primary key,
    file_object_id varchar(36) not null,
    workspace_id varchar(36) not null,
    bucket varchar(160) not null,
    object_key varchar(500) not null,
    object_generation bigint not null,
    attempts int not null default 0,
    claimed_at timestamp not null default current_timestamp,
    completed_at timestamp null,
    failed_at timestamp null,
    last_error varchar(500) null,
    constraint fk_file_delete_ticket_object foreign key (file_object_id) references file_object(id)
);

create index idx_file_delete_ticket_open on file_object_delete_ticket(completed_at, claimed_at);

-- 派生对象登记（FB-06.2）：按 workspace/source/snapshot/batchVersion/objectKind/bucket/key
-- 登记全部派生物，删除与对账走登记表，不靠猜字符串路径。
-- 状态：REGISTERED（发布成功，随来源存活）/ RELEASED（来源删除事务已登记清理意图）/
-- ORPHANED（发布失败或晚到写入，对象已失去发布机会，等待有界清理）/
-- CLEANED（物理清理完成，保留为对账事实）。
create table derived_file_object (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    source_id varchar(36) null,
    source_snapshot_id varchar(36) null,
    batch_version varchar(64) null,
    object_kind varchar(64) not null,
    bucket varchar(160) not null,
    object_key varchar(500) not null,
    sha256 varchar(64) null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_derived_object_key unique (bucket, object_key),
    constraint ck_derived_object_status check (status in ('REGISTERED', 'RELEASED', 'ORPHANED', 'CLEANED'))
);

create index idx_derived_object_source on derived_file_object(workspace_id, source_id, status);
create index idx_derived_object_snapshot on derived_file_object(source_snapshot_id, batch_version, status);
create index idx_derived_object_status on derived_file_object(status, created_at);
