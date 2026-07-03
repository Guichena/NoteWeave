create table task (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    task_type varchar(64) not null,
    task_status varchar(32) not null,
    target_type varchar(64) null,
    target_id varchar(36) null,
    progress_phase varchar(64) null,
    progress_message varchar(1000) null,
    result_ref varchar(500) null,
    error_message varchar(1000) null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_task_workspace foreign key (workspace_id) references workspace(id)
);

create index idx_task_status_created on task(task_status, created_at);

create table task_attempt (
    id varchar(36) primary key,
    task_id varchar(36) not null,
    attempt_no int not null,
    status varchar(32) not null,
    started_at timestamp null,
    finished_at timestamp null,
    error_message varchar(1000) null,
    constraint fk_task_attempt_task foreign key (task_id) references task(id)
);

create table task_event (
    id varchar(36) primary key,
    task_id varchar(36) not null,
    event_type varchar(64) not null,
    message varchar(1000) null,
    payload_json clob null,
    created_at timestamp not null default current_timestamp,
    constraint fk_task_event_task foreign key (task_id) references task(id)
);

create table task_outbox (
    id varchar(36) primary key,
    task_id varchar(36) not null,
    topic varchar(160) not null,
    message_key varchar(160) not null,
    payload_json clob not null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    sent_at timestamp null,
    constraint fk_task_outbox_task foreign key (task_id) references task(id)
);

create index idx_task_outbox_status_created on task_outbox(status, created_at);
