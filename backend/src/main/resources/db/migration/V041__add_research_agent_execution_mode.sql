-- MA3D: explicit isolation between legacy snapshot rebuild and incremental agent projection.
alter table research_run add column agent_execution_mode varchar(32) not null default 'SEQUENTIAL_V1';
create index idx_research_run_agent_execution_mode on research_run(agent_execution_mode, status);
