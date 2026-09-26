create index idx_task_event_task_created
    on task_event(task_id, created_at, id);
