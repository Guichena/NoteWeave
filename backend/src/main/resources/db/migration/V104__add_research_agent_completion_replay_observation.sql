alter table research_agent_completion
    add column replay_count int not null default 0;

alter table research_agent_completion
    add column last_replayed_at timestamp null;
