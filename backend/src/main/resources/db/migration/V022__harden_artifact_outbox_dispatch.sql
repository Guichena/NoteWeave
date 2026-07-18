alter table task_outbox add column attempt_count int not null default 0;
alter table task_outbox add column last_error varchar(1000) null;
alter table task_outbox add column claimed_at timestamp null;
alter table task_outbox add column next_attempt_at timestamp null;

create index idx_task_outbox_dispatch on task_outbox(topic, status, next_attempt_at, created_at);
