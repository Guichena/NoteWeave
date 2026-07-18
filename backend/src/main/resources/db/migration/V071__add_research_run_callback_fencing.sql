alter table research_run add column run_version int not null default 0;
alter table research_run add column attempt_no int not null default 1;
alter table research_run add column fencing_token bigint not null default 1;
