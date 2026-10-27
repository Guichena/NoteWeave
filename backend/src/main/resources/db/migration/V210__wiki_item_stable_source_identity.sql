-- NR19：自动资料页绑定稳定 source_id 身份。
-- 此前自动 Wiki 摄取以标题为唯一身份：不同目录上传同名文件会追加到同一页面并
-- 替换当前正文与引用；人工页与资料页同名也互相覆盖。本迁移为 knowledge_item
-- 增加 origin_source_id（自动资料页的来源绑定；概念页/人工页为 NULL），
-- 显示标题与唯一身份分离。不回填历史行——存量同名页保持现状，新页面按新身份创建。
alter table knowledge_item add column origin_source_id varchar(36) null;
create index idx_knowledge_item_origin_source on knowledge_item(origin_source_id);
