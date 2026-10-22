-- 约束抽取改为增量：每个会话记录已处理到的 USER 消息 seq，以及已经抽取过的 ops
-- （合并器输入），refresh 只对新增消息调用模型。模型调用因此可以放在切分会话行锁的
-- 事务之外，落库时再用 processed_seq/lock_version 做乐观检查。
-- 约束行本身仍写在 conversation_constraint_v2，本表只保存抽取进度与重放基线。
create table conversation_constraint_state_v2 (
    conversation_id varchar(36) primary key,
    workspace_id varchar(36) not null,
    processed_seq int not null default 0,
    steps_json longtext null,
    rule_version varchar(64) not null,
    lock_version int not null default 0,
    updated_at timestamp not null default current_timestamp,
    constraint fk_constraint_state_v2_conversation foreign key (conversation_id) references conversation(id),
    constraint fk_constraint_state_v2_workspace foreign key (workspace_id) references workspace(id),
    constraint ck_constraint_state_v2_processed check (processed_seq >= 0)
);
