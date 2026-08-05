create table auth_login_throttle (
    dimension_key char(64) primary key,
    dimension_type varchar(16) not null,
    failure_count int not null default 0,
    window_started_at timestamp not null default current_timestamp,
    blocked_until timestamp null,
    updated_at timestamp not null default current_timestamp
);

create index idx_auth_login_throttle_updated
    on auth_login_throttle(updated_at);

create table auth_login_security_event (
    id varchar(36) primary key,
    ip_hash char(64) not null,
    login_hash char(64) not null,
    outcome varchar(32) not null,
    failure_count int not null default 0,
    occurred_at timestamp not null default current_timestamp
);

create index idx_auth_login_security_event_ip
    on auth_login_security_event(ip_hash, occurred_at);

create index idx_auth_login_security_event_login
    on auth_login_security_event(login_hash, occurred_at);
