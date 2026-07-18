create table worker_callback_receipt (
    id varchar(36) primary key,
    task_id varchar(36) not null,
    idempotency_key varchar(255) not null,
    callback_type varchar(64) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_worker_callback_receipt_task foreign key (task_id) references task(id)
);

create unique index uq_worker_callback_receipt_key
    on worker_callback_receipt(task_id, idempotency_key);
