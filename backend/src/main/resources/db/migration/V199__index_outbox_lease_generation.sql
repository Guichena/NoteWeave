-- M-INDEX-LEASE：Memory / Wiki 索引发件箱的认领租约与单调代次（B-07）。
-- 认领只防并行重复投递；旧请求晚到由单调 generation + ES external version +
-- 删除墓碑文档（不物理删除）防复活，不依赖任选 TTL。
alter table memory_index_outbox add column desired_generation bigint not null default 1;
alter table memory_index_outbox add column lease_owner varchar(128) null;
alter table memory_index_outbox add column lease_until timestamp null;
alter table memory_index_outbox add column next_attempt_at timestamp null;

create index idx_memory_index_outbox_dispatch
    on memory_index_outbox(status, next_attempt_at, lease_until, created_at);

alter table wiki_index_outbox add column desired_generation bigint not null default 1;
alter table wiki_index_outbox add column lease_owner varchar(128) null;
alter table wiki_index_outbox add column lease_until timestamp null;
alter table wiki_index_outbox add column next_attempt_at timestamp null;

create index idx_wiki_index_outbox_dispatch
    on wiki_index_outbox(status, next_attempt_at, lease_until, created_at);

-- 条目级单调索引代次：UPSERT 与 DELETE 均递增；ES 写入携带该值作为 external version，
-- 迟到的旧 UPSERT 会因版本更低被拒绝，不能覆盖更新的 DELETE。
alter table memory_item add column index_generation bigint not null default 0;
alter table knowledge_item add column index_generation bigint not null default 0;
