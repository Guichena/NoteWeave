-- 语义槽位与记忆索引发件箱。
-- memory_item.slot_key 仍是作用域内的身份键，V080 的唯一约束不能改成共享语义槽。
-- 偏好槽位单独放在 semantic_slot_key。失效时间继续用 memory_runtime_revision.valid_until。

alter table memory_item
    add column semantic_slot_key varchar(256) null;

create index idx_memory_item_semantic_slot
    on memory_item(workspace_id, semantic_slot_key, status);

alter table memory_signal
    add column slot_key varchar(256) null;

alter table memory_signal
    add column slot_value varchar(512) null;

alter table memory_signal
    add column polarity varchar(16) null;

alter table memory_candidate
    add column slot_key varchar(256) null;

alter table memory_candidate
    add column slot_value varchar(512) null;

alter table memory_candidate
    add column polarity varchar(16) null;

alter table memory_candidate
    add column conflict_type varchar(32) null;

alter table memory_candidate
    add column conflicting_item_id varchar(36) null;

alter table memory_candidate
    add column conflicting_text longtext null;

create table memory_index_outbox (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    memory_item_id varchar(36) not null,
    operation varchar(16) not null,
    payload_json longtext null,
    status varchar(16) not null,
    attempts int not null default 0,
    last_error varchar(500) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create index idx_memory_index_outbox_pending
    on memory_index_outbox(status, created_at);
