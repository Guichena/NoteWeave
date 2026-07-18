alter table conversation
    add column updated_at timestamp not null default current_timestamp;
