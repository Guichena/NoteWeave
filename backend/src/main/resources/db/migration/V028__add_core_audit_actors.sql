alter table workspace add column created_by varchar(80) not null default 'SYSTEM:MIGRATION';
alter table workspace add column updated_by varchar(80) not null default 'SYSTEM:MIGRATION';

alter table workspace_member add column created_by varchar(80) not null default 'SYSTEM:MIGRATION';
alter table workspace_member add column updated_by varchar(80) not null default 'SYSTEM:MIGRATION';

alter table task add column created_by varchar(80) not null default 'SYSTEM:MIGRATION';
alter table task add column updated_by varchar(80) not null default 'SYSTEM:MIGRATION';

alter table document_upload add column created_by varchar(80) not null default 'SYSTEM:MIGRATION';
alter table document_upload add column updated_by varchar(80) not null default 'SYSTEM:MIGRATION';

alter table source add column created_by varchar(80) not null default 'SYSTEM:MIGRATION';
alter table source add column updated_by varchar(80) not null default 'SYSTEM:MIGRATION';

alter table conversation add column created_by varchar(80) not null default 'SYSTEM:MIGRATION';
alter table conversation add column updated_by varchar(80) not null default 'SYSTEM:MIGRATION';

alter table knowledge_item add column created_by varchar(80) not null default 'SYSTEM:MIGRATION';
alter table knowledge_item add column updated_by varchar(80) not null default 'SYSTEM:MIGRATION';

update workspace set created_by = owner_id, updated_by = owner_id;
update workspace_member set created_by = user_id, updated_by = user_id;
