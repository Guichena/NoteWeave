create table memory_item (
    id varchar(36) primary key,
    workspace_id varchar(36) null,
    owner_user_id varchar(36) null,
    memory_scope varchar(32) not null,
    scope_ref_key varchar(128) not null,
    slot_key varchar(256) not null,
    slot_schema_version varchar(64) not null,
    status varchar(32) not null,
    current_revision_id varchar(36) null,
    legacy_memory_object_id varchar(36) null,
    lock_version bigint not null default 0,
    last_confirmed_at timestamp null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_memory_item_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_memory_item_owner foreign key (owner_user_id) references users(id),
    constraint uk_memory_item_scope_slot unique (workspace_id, owner_user_id, memory_scope, scope_ref_key, slot_key),
    constraint uk_memory_item_legacy_object unique (legacy_memory_object_id)
);

create table memory_runtime_revision (
    id varchar(36) primary key,
    memory_item_id varchar(36) not null,
    workspace_id varchar(36) null,
    version_no int not null,
    status varchar(32) not null,
    confidence decimal(5,4) not null default 0.0000,
    valid_from timestamp not null default current_timestamp,
    valid_until timestamp null,
    normalized_value_json longtext null,
    display_text longtext not null,
    provenance_type varchar(64) not null,
    provenance_ref varchar(128) null,
    observation_id varchar(128) null,
    content_hash varchar(128) not null,
    supersedes_revision_id varchar(36) null,
    created_at timestamp not null default current_timestamp,
    constraint fk_memory_runtime_revision_item foreign key (memory_item_id) references memory_item(id),
    constraint fk_memory_runtime_revision_workspace foreign key (workspace_id) references workspace(id),
    constraint uk_memory_runtime_revision_number unique (memory_item_id, version_no),
    constraint uk_memory_runtime_revision_observation unique (observation_id)
);

create index idx_memory_item_runtime_recall
    on memory_item(workspace_id, memory_scope, status, current_revision_id);

create index idx_memory_runtime_revision_active
    on memory_runtime_revision(memory_item_id, status, valid_from, valid_until);

create table memory_event (
    id varchar(36) primary key,
    memory_item_id varchar(36) not null,
    event_type varchar(64) not null,
    idempotency_key varchar(128) not null,
    run_type varchar(64) null,
    run_id varchar(128) null,
    payload_json longtext null,
    created_at timestamp not null default current_timestamp,
    constraint fk_memory_event_item foreign key (memory_item_id) references memory_item(id),
    constraint uk_memory_event_idempotency unique (memory_item_id, event_type, idempotency_key)
);

create index idx_memory_event_item_created
    on memory_event(memory_item_id, created_at);

insert into memory_item(
    id, workspace_id, owner_user_id, memory_scope, scope_ref_key, slot_key,
    slot_schema_version, status, current_revision_id, legacy_memory_object_id,
    lock_version, last_confirmed_at
)
select o.id,
       o.workspace_id,
       o.user_id,
       o.memory_scope,
       case when o.memory_scope = 'USER' then o.user_id else o.workspace_id end,
       concat('legacy:', o.id),
       'legacy-object-v1',
       case when o.status = 'ACTIVE' then 'ACTIVE'
            when o.status = 'STALE' then 'STALE'
            else 'DELETED' end,
       o.latest_version_id,
       o.id,
       0,
       o.updated_at
from memory_object o;

insert into memory_runtime_revision(
    id, memory_item_id, workspace_id, version_no, status, confidence,
    valid_from, valid_until, normalized_value_json, display_text,
    provenance_type, provenance_ref, content_hash, supersedes_revision_id
)
select v.id,
       v.memory_object_id,
       v.workspace_id,
       v.version_no,
       case when v.status = 'ACTIVE' then 'ACTIVE'
            when v.status = 'SUPERSEDED' then 'SUPERSEDED'
            else 'REJECTED' end,
       v.risk_score,
       v.valid_from,
       v.valid_to,
       null,
       v.canonical_statement,
       'LEGACY_MEMORY_VERSION',
       v.id,
       v.id,
       v.supersedes_version_id
from memory_version v;
