-- KB-01：产物写回知识库时绑定来源血缘。
-- origin 标记知识对象的来源性质：ARTIFACT 表示由产物版本写回，读路径必须按引用来源判可读性；
-- 人工笔记为空，没有引用来源时照常可读。
alter table knowledge_item add column origin varchar(32) null;

-- 产物引用按来源级别绑定（冻结来源 + 快照），不一定落在具体分块上，
-- 因此允许 citation.source_chunk_id 为空；有分块的引用行为不变。
alter table citation modify column source_chunk_id varchar(36) null;
