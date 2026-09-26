create table research_evidence_validation (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    evidence_id varchar(36) not null,
    candidate_id varchar(36) null,
    cell_id varchar(36) null,
    binding_key varchar(191) not null,
    validation_schema_version varchar(64) not null,
    validation_mode varchar(16) not null,
    deterministic_status varchar(32) not null,
    semantic_status varchar(32) not null,
    final_status varchar(32) not null,
    reason_codes_json json not null,
    typed_facts_json json not null,
    input_digest varchar(71) not null,
    validator_digest varchar(71) not null,
    completion_id varchar(36) null,
    audit_task_id varchar(36) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_evidence_validation_run
        foreign key (research_run_id) references research_run(id),
    constraint fk_research_evidence_validation_evidence
        foreign key (evidence_id) references source_evidence(id),
    constraint fk_research_evidence_validation_candidate
        foreign key (candidate_id) references research_agent_candidate(id),
    constraint fk_research_evidence_validation_cell
        foreign key (cell_id) references research_cell(id),
    constraint fk_research_evidence_validation_completion
        foreign key (completion_id) references research_agent_completion(id),
    constraint fk_research_evidence_validation_audit_task
        foreign key (audit_task_id) references research_agent_task(id)
);

create unique index uq_research_evidence_validation_input
    on research_evidence_validation(
        research_run_id, binding_key, evidence_id, validation_schema_version, input_digest
    );

create index idx_research_evidence_validation_final
    on research_evidence_validation(research_run_id, final_status, created_at);
