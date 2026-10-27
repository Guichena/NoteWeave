-- ART-02：DEGRADED 版本的文件补偿不能再占满批次。
-- 旧实现每轮都取「最早创建的 10 条 DEGRADED」，修不好的版本每轮都排在队头，
-- 结果固定的一批坏版本把每个批次占满，后面新变 DEGRADED 的版本永远轮不到。
-- 计数与下一次尝试时间用于「退避到队尾」，终态时间用于「不再参与自动补偿」。
alter table artifact_version add column reconcile_attempts int not null default 0;
alter table artifact_version add column reconcile_next_attempt_at timestamp(3);
alter table artifact_version add column reconcile_exhausted_at timestamp(3);

create index idx_artifact_version_file_reconcile
    on artifact_version(delivery_status, reconcile_exhausted_at, reconcile_next_attempt_at);
