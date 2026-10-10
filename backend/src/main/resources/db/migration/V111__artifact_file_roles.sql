alter table artifact_file add column file_role varchar(64) not null default '';
alter table artifact_file add column variant varchar(64) not null default '';
alter table artifact_file add column sequence_no int not null default 0;

update artifact_file
set file_role = case file_format
    when 'MARKDOWN' then 'PRIMARY_MARKDOWN'
    when 'PDF' then 'PRIMARY_PDF'
    else concat('LEGACY_', file_format)
end;

drop index uq_artifact_file_version_format on artifact_file;
create unique index uq_artifact_file_version_role
    on artifact_file(artifact_version_id, file_role, variant, sequence_no);
