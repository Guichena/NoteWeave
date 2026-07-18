alter table workspace
    add column acl_version bigint not null default 1;

alter table workspace_member
    add column status varchar(32) not null default 'ACTIVE';

alter table workspace_member
    add column updated_at timestamp not null default current_timestamp;

create index idx_workspace_member_access
    on workspace_member(workspace_id, user_id, status);
