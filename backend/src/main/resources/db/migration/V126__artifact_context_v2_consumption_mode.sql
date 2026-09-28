alter table artifact_context_v2_shadow_snapshot
    add column consumption_mode varchar(16) not null default 'SHADOW';

alter table artifact_context_v2_shadow_snapshot
    add constraint ck_artifact_context_v2_consumption_mode
    check (consumption_mode in ('SHADOW', 'ACTIVE'));
