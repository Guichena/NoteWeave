-- 生成身份跨租约保留，复制片段不会重复计算同一次生成的模型消耗。
create table research_synthesis_generation (
    task_id varchar(36) not null,
    lease_epoch int not null,
    fencing_token bigint not null,
    input_digest varchar(71) not null,
    origin_key varchar(71) not null,
    content_digest varchar(71) not null,
    part_count int not null,
    total_bytes int not null,
    llm_calls int not null,
    created_at timestamp not null default current_timestamp,
    primary key(task_id, lease_epoch, fencing_token),
    constraint fk_synthesis_generation_task foreign key(task_id) references research_agent_task(id)
);
create index ix_synthesis_generation_input on research_synthesis_generation(task_id, input_digest, lease_epoch);
create index ix_synthesis_upload_retention on research_synthesis_upload_part(created_at, task_id);
