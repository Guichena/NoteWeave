-- MEM-02：回执重投的失败计数。
-- 旧实现里重投失败只打日志：next_attempt_at 保持不变，回执一直排在最前面，
-- 50 条坏回执就能把 scanDue 的批次占满，新回执永远轮不到（队头阻塞）。
-- 计数用于「退避到队尾」与「达到上限进入终态」两条出口。
alter table memory_processing_receipt add column redelivery_failures int not null default 0;
