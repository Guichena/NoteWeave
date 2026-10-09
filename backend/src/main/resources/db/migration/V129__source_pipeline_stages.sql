-- 资料处理拆成解析、切片、向量化、索引四个异步阶段。
-- processing_stage 记录快照当前所处（或失败时停在）的阶段：EXTRACTING / CHUNKING / EMBEDDING / INDEXING / READY。
-- 检索索引失败后按退避时间自动重试：index_attempt_count 为已自动重试的次数，next_index_retry_at 为下次重试时间。
alter table source_snapshot add column processing_stage varchar(32) null;
alter table source_snapshot add column index_attempt_count int not null default 0;
alter table source_snapshot add column next_index_retry_at timestamp null;

update source_snapshot set processing_stage = 'READY' where index_status in ('INDEXED', 'DISABLED');

create index idx_source_snapshot_index_retry on source_snapshot(index_status, next_index_retry_at);
