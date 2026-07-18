create table research_external_snapshot (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    research_agent_task_id varchar(36) not null,
    window_id varchar(64) not null,
    source_id varchar(64) not null,
    source_title varchar(300) not null,
    source_url varchar(2048) not null,
    source_domain varchar(255) not null,
    provider varchar(64) not null,
    adapter varchar(64) not null,
    snapshot_key varchar(512) not null,
    content_text longtext not null,
    content_sha256 char(64) not null,
    archive_status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_external_snapshot_run
        foreign key (research_run_id) references research_run(id),
    constraint fk_research_external_snapshot_task
        foreign key (research_agent_task_id) references research_agent_task(id),
    constraint uk_research_external_snapshot_identity
        unique (research_agent_task_id, window_id, source_id),
    constraint uk_research_external_snapshot_key
        unique (research_agent_task_id, snapshot_key)
);

create index idx_research_external_snapshot_run
    on research_external_snapshot(research_run_id, archive_status, created_at);
