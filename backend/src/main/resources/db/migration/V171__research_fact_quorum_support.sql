-- 逐事实保存双路执行与来源身份，单路佐证不能直接升级为高风险事实。
alter table research_requirement_fact_support add column support_task_id varchar(36) null;
alter table research_requirement_fact_support add column quorum_group_key varchar(128) null;
alter table research_requirement_fact_support add column candidate_quorum int not null default 1;
alter table research_requirement_fact_support add column candidate_slot int not null default 1;
alter table research_requirement_fact_support add column source_identities_json json null;
alter table research_requirement_fact_support add column source_fingerprints_json json null;
create index idx_requirement_fact_quorum on research_requirement_fact_support(fact_id, quorum_group_key, candidate_slot);
