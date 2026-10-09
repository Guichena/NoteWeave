-- 会话摘要改为增量生成：记录每一版摘要基于哪一版旧摘要生成，以及生成方式
-- （LLM_INCREMENTAL / LLM_FULL / EXTRACTIVE）。旧数据都是抽取式摘要。
alter table segment_summary_revision add column base_revision_id varchar(36) null;
alter table segment_summary_revision add column summary_method varchar(32) null;

alter table conversation_topic_summary_revision_v2 add column base_revision_id varchar(36) null;
alter table conversation_topic_summary_revision_v2 add column summary_method varchar(32) null;

update segment_summary_revision set summary_method = 'EXTRACTIVE' where status = 'READY';
update conversation_topic_summary_revision_v2 set summary_method = 'EXTRACTIVE' where status = 'READY';
