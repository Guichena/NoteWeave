-- 证据累积的 Beta 参数。revision.confidence 继续保留原用途，不拿来存后验均值。

alter table memory_item
    add column evidence_alpha decimal(12,4) not null default 1.5000;

alter table memory_item
    add column evidence_beta decimal(12,4) not null default 1.5000;

alter table memory_item
    add column reinforcement_count int not null default 0;

alter table memory_item
    add column last_reinforced_at timestamp null;

alter table memory_signal
    add column evidence_band varchar(16) null;

create table memory_evidence_session (
    id varchar(36) primary key,
    memory_item_id varchar(36) not null,
    session_key varchar(160) not null,
    direction varchar(16) not null,
    weight decimal(8,4) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_memory_evidence_session_item foreign key (memory_item_id) references memory_item(id),
    constraint uk_memory_evidence_session unique (memory_item_id, session_key, direction)
);
