alter table context_v2_workspace_rollout drop constraint ck_context_v2_rollout_mode;

alter table context_v2_workspace_rollout add constraint ck_context_v2_rollout_mode
    check (mode in ('OFF', 'SHADOW', 'ACTIVE'));
