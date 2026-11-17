-- 范围判定来自冻结契约和归档来源，未知的时间信息保留为 UNKNOWN。
alter table research_requirement_fact_support add column source_scope_json json null;
