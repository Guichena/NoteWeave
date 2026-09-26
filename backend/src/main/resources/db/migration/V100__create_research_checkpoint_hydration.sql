create table research_checkpoint_hydration_snapshot (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    checkpoint_id varchar(36) not null,
    checkpoint_seq int not null,
    schema_version varchar(64) not null,
    payload_json longtext not null,
    content_size bigint not null,
    payload_sha256 char(64) not null,
    canonical_digest varchar(71) not null,
    ledger_digest varchar(128) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_checkpoint_hydration_run
        foreign key (research_run_id) references research_run(id),
    constraint fk_research_checkpoint_hydration_checkpoint
        foreign key (checkpoint_id) references research_agent_checkpoint(id)
);

create unique index uq_research_checkpoint_hydration_checkpoint
    on research_checkpoint_hydration_snapshot(checkpoint_id);

create unique index uq_research_checkpoint_hydration_seq
    on research_checkpoint_hydration_snapshot(research_run_id, checkpoint_seq);

alter table research_run
    add column resume_mode varchar(32) null;
