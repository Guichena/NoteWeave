-- TR-02：转写提交要有上限，不能对同一份原件无限重下。
-- 旧实现每次提交失败只做有限退避（最多 5 分钟）并留在 PENDING，
-- Worker 持续 429 时会按退避节奏反复下载原件（音视频原件可达上百 MB），
-- 直到看门狗按 480 分钟超时收尾才停。
alter table source_transcription_submission add column max_attempts int not null default 8;
