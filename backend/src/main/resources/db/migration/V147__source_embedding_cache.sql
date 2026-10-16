create table source_embedding_cache (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    embedding_version varchar(128) not null,
    chunker_version varchar(128) not null,
    context_prompt_version varchar(128) not null,
    content_hash varchar(80) not null,
    full_input_hash varchar(80) not null,
    effective_input_hash varchar(80) not null,
    embedding_model varchar(300) not null,
    embedding_dimensions int not null,
    vector_json longtext not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_source_embedding_cache_workspace foreign key (workspace_id) references workspace(id),
    constraint uk_source_embedding_cache_key unique (
        workspace_id, embedding_version, chunker_version, context_prompt_version, content_hash
    )
);
