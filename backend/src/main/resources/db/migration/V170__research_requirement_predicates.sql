-- 显式需求保留原问题依据；对象数量和事实数量使用不同的完成条件。
alter table research_requirement add column completion_mode varchar(32) not null default 'SINGLE';
alter table research_requirement add column minimum_count int not null default 1;
alter table research_requirement add column question_quote varchar(1000) null;
alter table research_requirement add column scope_type varchar(16) not null default 'CELL';
alter table research_requirement add column scope_json json null;
alter table research_requirement_observation add column coverage_status varchar(32) not null default 'SATISFIED';
