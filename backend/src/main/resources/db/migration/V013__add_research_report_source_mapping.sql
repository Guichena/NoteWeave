alter table source add column generated_by varchar(64) null;
alter table source add column generated_ref_id varchar(128) null;

create index idx_source_generated_ref on source(generated_by, generated_ref_id);

alter table research_run add column report_source_id varchar(36) null;
