-- IDX-04：检索索引重建的工作台互斥锁。
-- 同一工作台同一时刻只允许一次重建：主键冲突即表示已有重建在跑，
-- 避免两次重建各自创建新索引后互相覆盖别名。
create table retrieval_index_rebuild_lock (
    workspace_id varchar(36) primary key,
    build_token varchar(64) not null,
    acquired_at timestamp not null default current_timestamp,
    constraint fk_retrieval_rebuild_lock_workspace foreign key (workspace_id) references workspace(id)
);
