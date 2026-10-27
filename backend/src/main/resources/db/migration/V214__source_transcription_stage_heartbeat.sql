-- TR-01：转写看门狗的计时锚点。
-- 旧实现用 source.updated_at 计时：任何无关的资料更新（改标题、目录版本号变更等）都会重置计时，
-- 而一段 4 小时的录音即使 Worker 仍在正常转写，也会在固定时限后被误杀。
-- stage_started_at 记录进入转写阶段的时刻；heartbeat_at 预留给 Worker 上报进度
-- （当前没有上报入口，写入值与 stage_started_at 相同，接入心跳后无需再改看门狗）。
alter table source_transcription_submission add column stage_started_at timestamp null;
alter table source_transcription_submission add column heartbeat_at timestamp null;

-- 存量记录按行创建时间回填，避免迁移后旧提交因为缺锚点而永远不超时。
update source_transcription_submission
set stage_started_at = created_at, heartbeat_at = created_at
where stage_started_at is null;
