-- NULL 明确表示历史请求消耗未知；仅新批次由应用显式写入 0，不追溯伪造历史成本。
alter table source_embedding_batch add column embedding_requests_used int null;
alter table source_embedding_batch add column next_recovery_at timestamp null;

create index idx_embedding_batch_recovery on source_embedding_batch(status, next_recovery_at);
