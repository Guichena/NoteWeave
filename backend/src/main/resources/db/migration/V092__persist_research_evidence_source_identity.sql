alter table source_evidence
    add column source_origin varchar(2048) null;

alter table source_evidence
    add column source_domain varchar(255) null;

alter table source_evidence
    add column lineage_digest char(64) null;

create index idx_source_evidence_domain
    on source_evidence(research_run_id, source_domain);

create index idx_source_evidence_lineage
    on source_evidence(research_run_id, lineage_digest);
