alter table source add column summary varchar(1000) null;
alter table source add column tags_json clob null;
alter table source add column metadata_json clob null;

create index idx_source_workspace_type_updated on source(workspace_id, source_type, updated_at);
