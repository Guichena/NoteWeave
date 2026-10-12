-- 话题切分的降级原因和不确定边界复核状态。旧行两列都为空，已有插入语句不用改。
alter table conversation_topic_segment_v2 add column degradation_reason varchar(80) null;
alter table conversation_topic_segment_v2 add column boundary_review varchar(32) null;
