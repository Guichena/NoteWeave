-- 报告片段绑定具体租约；正式完成时核对整份内容后再进行成文校验与原子提交。
create table research_synthesis_upload_part (
    id varchar(36) primary key,
    task_id varchar(36) not null,
    lease_epoch int not null,
    fencing_token bigint not null,
    content_digest varchar(71) not null,
    part_index int not null,
    part_count int not null,
    total_bytes int not null,
    part_digest varchar(71) not null,
    content_bytes mediumblob not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_synthesis_upload_task foreign key (task_id) references research_agent_task(id)
);
create unique index uq_synthesis_upload_part on research_synthesis_upload_part(task_id, lease_epoch, fencing_token, part_index);
