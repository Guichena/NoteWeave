create table users (
    id varchar(36) primary key,
    username varchar(80) not null,
    email varchar(160) not null,
    display_name varchar(120) not null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_users_username unique (username),
    constraint uk_users_email unique (email)
);

create table user_session (
    id varchar(36) primary key,
    user_id varchar(36) not null,
    session_token varchar(128) not null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    expires_at timestamp null,
    constraint fk_user_session_user foreign key (user_id) references users(id)
);

create table workspace (
    id varchar(36) primary key,
    owner_id varchar(36) not null,
    name varchar(160) not null,
    description varchar(1000) null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_workspace_owner foreign key (owner_id) references users(id)
);

create index idx_workspace_owner_updated on workspace(owner_id, updated_at);

create table workspace_member (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    user_id varchar(36) not null,
    role varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_workspace_member_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_workspace_member_user foreign key (user_id) references users(id),
    constraint uk_workspace_member_user unique (workspace_id, user_id)
);

create table topic_scope (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    name varchar(160) not null,
    description varchar(1000) null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_topic_scope_workspace foreign key (workspace_id) references workspace(id)
);

insert into users(id, username, email, display_name, status)
values ('local-user', 'local-user', 'local-user@noteweave.local', 'Local User', 'ACTIVE');
