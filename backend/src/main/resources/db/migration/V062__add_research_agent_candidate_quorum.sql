-- MA5Q: server-authoritative high-risk policy and durable candidate slots.

alter table research_cell
    add column high_risk boolean not null default false;

alter table research_agent_task add column logical_task_key varchar(191) null;
alter table research_agent_task add column quorum_group_key varchar(128) null;
alter table research_agent_task add column candidate_quorum int not null default 1;
alter table research_agent_task add column candidate_slot int not null default 1;

alter table research_agent_task
    add constraint ck_research_agent_task_candidate_quorum
        check (candidate_quorum between 1 and 2);
alter table research_agent_task
    add constraint ck_research_agent_task_candidate_slot
        check (candidate_slot between 1 and candidate_quorum);
alter table research_agent_task
    add constraint ck_research_agent_task_quorum_group
        check (candidate_quorum = 1 or quorum_group_key is not null);

create unique index uq_research_agent_task_quorum_slot
    on research_agent_task(research_run_id, quorum_group_key, candidate_slot);

alter table research_agent_candidate add column quorum_group_key varchar(128) null;
alter table research_agent_candidate add column candidate_slot int not null default 1;
alter table research_agent_candidate add column source_domains_json json null;
alter table research_agent_candidate add column blind_digest varchar(71) null;

alter table research_agent_candidate
    add constraint ck_research_agent_candidate_slot check (candidate_slot between 1 and 2);

create unique index uq_research_agent_candidate_quorum_slot
    on research_agent_candidate(research_run_id, quorum_group_key, candidate_slot);
