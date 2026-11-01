-- 引用归属：标记这条引用是某条消息生成时自有的副本。
-- 删除消息时只清理自有引用的正文副本；被其他页面或其他消息共用的引用行保持不动。
alter table citation add column owner_message_id varchar(36) null;

create index idx_citation_owner_message on citation(workspace_id, owner_message_id);
