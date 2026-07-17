-- MA4J: immutable report artifact committed with Run and parent-task finalization.
create table research_agent_report_artifact (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    generation_key varchar(160) not null,
    report_digest varchar(128) not null,
    report_title varchar(300) not null,
    report_markdown longtext not null,
    verified_cell_count int not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_agent_report_artifact_run foreign key (research_run_id) references research_run(id)
);

create unique index uq_research_agent_report_artifact_run
    on research_agent_report_artifact(research_run_id);
create unique index uq_research_agent_report_artifact_generation
    on research_agent_report_artifact(research_run_id, generation_key);
