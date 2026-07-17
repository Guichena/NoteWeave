alter table research_run add column resumed_from_research_run_id varchar(36) null;
alter table research_run add column resumed_from_checkpoint_no int null;

create index idx_research_run_resumed_from on research_run(resumed_from_research_run_id, resumed_from_checkpoint_no);
