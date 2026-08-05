alter table users add column password_hash varchar(512) null;

alter table user_session add column token_hash char(64) null;
alter table user_session add column refresh_token_hash char(64) null;
alter table user_session add column refresh_expires_at timestamp null;
alter table user_session add column revoked_at timestamp null;
alter table user_session add column last_used_at timestamp null;

create unique index uk_user_session_token_hash on user_session(token_hash);
create unique index uk_user_session_refresh_token_hash on user_session(refresh_token_hash);
create index idx_user_session_user_status on user_session(user_id, status, expires_at);
