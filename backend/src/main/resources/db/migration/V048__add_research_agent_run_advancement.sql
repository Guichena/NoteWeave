-- MA4I: durable, idempotent coordinator advancement receipts.
create table research_agent_run_advancement (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    advance_key varchar(200) not null,
    coordinator_id varchar(160) not null,
    expected_checkpoint_seq int not null,
    decision_digest varchar(128) not null,
    checkpoint_id varchar(36) not null,
    checkpoint_seq int not null,
    lease_epoch int not null,
    fencing_token bigint not null,
    lease_expires_at timestamp not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_agent_run_advancement_run foreign key (research_run_id) references research_run(id),
    constraint fk_research_agent_run_advancement_checkpoint foreign key (checkpoint_id) references research_agent_checkpoint(id)
);

create unique index uq_research_agent_run_advancement_key
    on research_agent_run_advancement(research_run_id, advance_key);
create index idx_research_agent_run_advancement_lease
    on research_agent_run_advancement(research_run_id, lease_expires_at);
