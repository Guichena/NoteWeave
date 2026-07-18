alter table memory_usage_log
    modify column memory_object_id varchar(36) null;

alter table memory_item
    add column utility_score decimal(5,4) not null default 0.5000;

alter table memory_item
    add column application_count int not null default 0;

alter table memory_item
    add column positive_outcome_count int not null default 0;

alter table memory_item
    add column negative_outcome_count int not null default 0;

alter table memory_item
    add column edit_outcome_count int not null default 0;

alter table memory_item
    add column retry_outcome_count int not null default 0;

alter table memory_item
    add column review_status varchar(32) not null default 'APPROVED';

alter table memory_item
    add column outcome_policy_version varchar(64) not null default 'memory-outcome-policy-v1';

alter table memory_item
    add column last_outcome_at timestamp null;

update memory_item i
set utility_score = (
        select o.utility_score from memory_object o where o.id = i.legacy_memory_object_id
    ),
    application_count = (
        select o.application_count from memory_object o where o.id = i.legacy_memory_object_id
    ),
    positive_outcome_count = (
        select o.positive_outcome_count from memory_object o where o.id = i.legacy_memory_object_id
    ),
    negative_outcome_count = (
        select o.negative_outcome_count from memory_object o where o.id = i.legacy_memory_object_id
    ),
    edit_outcome_count = (
        select o.edit_outcome_count from memory_object o where o.id = i.legacy_memory_object_id
    ),
    retry_outcome_count = (
        select o.retry_outcome_count from memory_object o where o.id = i.legacy_memory_object_id
    ),
    review_status = (
        select o.review_status from memory_object o where o.id = i.legacy_memory_object_id
    ),
    outcome_policy_version = (
        select o.outcome_policy_version from memory_object o where o.id = i.legacy_memory_object_id
    ),
    last_outcome_at = (
        select o.last_outcome_at from memory_object o where o.id = i.legacy_memory_object_id
    )
where i.legacy_memory_object_id is not null;

alter table memory_usage_log
    add column memory_item_id varchar(36) null;

alter table memory_usage_log
    add column memory_revision_id varchar(36) null;

update memory_usage_log
set memory_item_id = memory_object_id,
    memory_revision_id = memory_version_id
where memory_item_id is null;

alter table memory_usage_log
    add constraint fk_memory_usage_item
        foreign key (memory_item_id) references memory_item(id);

alter table memory_usage_log
    add constraint fk_memory_usage_revision
        foreign key (memory_revision_id) references memory_runtime_revision(id);

create index idx_memory_usage_canonical_target
    on memory_usage_log(workspace_id, target_type, target_id, memory_item_id);

update memory_runtime_revision r
set normalized_value_json = (
    select json_object(
        'task_neighborhoods', v.task_neighborhood_json,
        'compile_hints', v.compile_policy_json,
        'utility_score', o.utility_score
    )
    from memory_version v
    join memory_object o on o.id = v.memory_object_id
    where v.id = r.provenance_ref
      and v.memory_object_id = r.memory_item_id
)
where r.provenance_type = 'LEGACY_MEMORY_VERSION'
  and r.normalized_value_json is null
  and exists (
      select 1
      from memory_version v
      where v.id = r.provenance_ref
        and v.memory_object_id = r.memory_item_id
  );
