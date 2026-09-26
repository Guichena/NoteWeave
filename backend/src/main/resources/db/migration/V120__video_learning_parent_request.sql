create table video_learning_request (
    id varchar(36) primary key,
    workspace_id varchar(36) not null,
    client_request_id varchar(120) not null,
    request_digest char(64) not null,
    video_url varchar(500) not null,
    part_no int not null,
    language varchar(12) not null,
    frame_density varchar(12) not null,
    asr_fallback varchar(12) not null,
    template_version varchar(80) not null,
    user_requirement varchar(4000) not null,
    material_task_id varchar(36) null,
    material_bundle_id varchar(36) null,
    knowledge_plan_id varchar(36) null,
    material_state varchar(24) not null default 'QUEUED',
    cancellation_requested boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint fk_video_learning_request_workspace foreign key (workspace_id) references workspace(id),
    constraint fk_video_learning_request_task foreign key (material_task_id) references task(id),
    constraint fk_video_learning_request_bundle foreign key (material_bundle_id)
        references artifact_video_material_bundle(id),
    constraint fk_video_learning_request_plan foreign key (knowledge_plan_id)
        references artifact_video_knowledge_plan(id),
    constraint uq_video_learning_request_client unique (workspace_id, client_request_id),
    constraint uq_video_learning_request_task unique (material_task_id),
    constraint ck_video_learning_request_part check (part_no between 1 and 1000),
    constraint ck_video_learning_request_material_state check
        (material_state in ('QUEUED', 'RUNNING', 'READY', 'FAILED', 'CANCELLED', 'DEGRADED'))
);

create table video_learning_request_choice (
    request_id varchar(36) not null,
    skill_key varchar(64) not null,
    artifact_job_id varchar(36) null,
    primary key (request_id, skill_key),
    constraint fk_video_learning_choice_request foreign key (request_id)
        references video_learning_request(id),
    constraint fk_video_learning_choice_job foreign key (artifact_job_id)
        references artifact_job(id),
    constraint uq_video_learning_choice_job unique (artifact_job_id),
    constraint ck_video_learning_choice_skill check (skill_key in
        ('knowledge_blog', 'interview_qa', 'video_learning_deck', 'bilibili_course_note_pdf'))
);

create index idx_video_learning_request_workspace_created
    on video_learning_request(workspace_id, created_at);
