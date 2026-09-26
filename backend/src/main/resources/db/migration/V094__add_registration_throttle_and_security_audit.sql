create table auth_registration_throttle (
    dimension_key char(64) primary key,
    dimension_type varchar(24) not null,
    attempt_count int not null default 0,
    window_started_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create index idx_auth_registration_throttle_updated
    on auth_registration_throttle(updated_at);

create table auth_registration_security_event (
    id varchar(36) primary key,
    ip_hash char(64) not null,
    username_hash char(64) not null,
    email_hash char(64) not null,
    outcome varchar(32) not null,
    attempt_count int not null default 0,
    occurred_at timestamp not null default current_timestamp
);

create index idx_auth_registration_event_ip
    on auth_registration_security_event(ip_hash, occurred_at);

create index idx_auth_registration_event_username
    on auth_registration_security_event(username_hash, occurred_at);

create index idx_auth_registration_event_email
    on auth_registration_security_event(email_hash, occurred_at);
