create table memory_review_decision (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    review_kind varchar(32) not null,
    review_id varchar(36) not null,
    decision varchar(32) not null,
    reason varchar(500) null,
    actor_user_id varchar(36) not null,
    result_object_id varchar(36) null,
    revoked_object_ids_json longtext null,
    created_at timestamp not null default current_timestamp,
    constraint fk_memory_review_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_memory_review_actor foreign key (actor_user_id) references users(id)
);

create index idx_memory_review_workspace_created
    on memory_review_decision(workspace_id, review_kind, created_at);
