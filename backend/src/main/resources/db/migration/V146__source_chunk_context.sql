-- 上下文说明与引用原文分开保存；失败原因和真实调用次数用于批次恢复。
alter table source_chunk add column context_text longtext null;
alter table source_chunk add column context_prompt_version varchar(160) null;
alter table source_chunk add column context_input_hash varchar(64) null;
alter table source_chunk add column context_reason varchar(100) null;
alter table source_chunk add column context_model_calls int not null default 0;

-- 只缓存成功生成的说明，工作台、配置版本和完整输入共同决定缓存资格。
create table source_chunk_context_cache (
    workspace_id varchar(64) not null,
    context_prompt_version varchar(160) not null,
    input_hash varchar(64) not null,
    context_text longtext not null,
    created_at timestamp not null default current_timestamp,
    primary key (workspace_id, context_prompt_version, input_hash)
);
