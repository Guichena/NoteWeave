-- 等价证据与未选中的候选簇均保留原结论，语义审计按独立版本号校验。
alter table research_agent_candidate add column equivalent_claims_json json null;
alter table research_agent_candidate add column source_fingerprints_json json null;
alter table research_cell add column semantic_revision int not null default 0;
alter table research_cell add column semantic_audit_status varchar(32) not null default 'NOT_REQUIRED';

create table research_claim_cluster (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    task_id varchar(36) not null,
    completion_id varchar(36) not null,
    cluster_key varchar(64) not null,
    cell_key varchar(160) not null,
    base_cell_version int not null,
    plan_revision int not null,
    entity_set_version int not null,
    claim_text longtext not null,
    evidence_keys_json json not null,
    equivalent_claims_json json not null,
    confidence_ppm int not null,
    created_at timestamp not null default current_timestamp
);
create unique index uq_research_claim_cluster_key on research_claim_cluster(research_run_id, cluster_key);
create index idx_research_claim_cluster_cell on research_claim_cluster(research_run_id, cell_key, base_cell_version);

create table research_semantic_check (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    task_id varchar(36) not null,
    cell_key varchar(160) not null,
    cell_version int not null,
    semantic_revision int not null,
    left_cluster_key varchar(64) not null,
    right_cluster_key varchar(64) not null,
    verdict varchar(32) not null,
    equivalence_verdict varchar(32) not null,
    checked_at timestamp not null default current_timestamp
);
create index idx_research_semantic_check_cell on research_semantic_check(research_run_id, cell_key, semantic_revision);

create view research_formal_eligible_cell as
select rc.* from research_cell rc
join research_counter_eligible_cell eligible on eligible.id = rc.id
where rc.semantic_audit_status in ('NOT_REQUIRED', 'PASS');
