create table artifact_context_v2_shadow_snapshot (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    input_snapshot_id varchar(36) not null,
    status varchar(24) not null,
    projection_json longtext not null,
    projection_sha256 char(64) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_artifact_context_shadow_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_artifact_context_shadow_input foreign key (input_snapshot_id) references artifact_run_input_snapshot(id),
    constraint uq_artifact_context_shadow_input unique (input_snapshot_id),
    constraint ck_artifact_context_shadow_status check (status in ('READY', 'REDACTED'))
);

create table artifact_context_v2_shadow_ref (
    snapshot_id varchar(36) not null,
    ref_type varchar(32) not null,
    ref_id varchar(36) not null,
    primary key (snapshot_id, ref_type, ref_id),
    constraint fk_artifact_context_shadow_ref_snapshot foreign key (snapshot_id)
        references artifact_context_v2_shadow_snapshot(id),
    constraint ck_artifact_context_shadow_ref_type check (ref_type in ('MEMORY_REVISION'))
);

create index idx_artifact_context_shadow_ref_lookup
    on artifact_context_v2_shadow_ref(ref_type, ref_id);
