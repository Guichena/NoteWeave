create table context_v2_workspace_rollout (
    workspace_id varchar(36) primary key,
    mode varchar(16) not null,
    lock_version int not null default 0,
    updated_at timestamp not null default current_timestamp,
    constraint fk_context_v2_rollout_workspace foreign key (workspace_id) references workspace(id),
    constraint ck_context_v2_rollout_mode check (mode in ('OFF', 'SHADOW'))
);
