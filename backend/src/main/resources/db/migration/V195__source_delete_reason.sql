-- 资料删除原因：USER 表示用户主动删除，COMPLIANCE 表示合规删除。
-- 历史删除记录没有原因信息，统一按 USER 解释。
alter table source add column delete_reason varchar(16) not null default 'USER';

alter table source add constraint chk_source_delete_reason
    check (delete_reason in ('USER', 'COMPLIANCE'));
