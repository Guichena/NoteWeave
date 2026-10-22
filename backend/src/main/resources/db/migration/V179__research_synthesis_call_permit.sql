create table research_synthesis_call_permit (
    task_id varchar(36) not null,
    lease_epoch int not null,
    fencing_token bigint not null,
    call_index int not null,
    purpose varchar(96) not null,
    created_at timestamp not null default current_timestamp,
    primary key(task_id, lease_epoch, fencing_token, call_index),
    constraint fk_synthesis_call_task foreign key(task_id) references research_agent_task(id)
);
create index idx_synthesis_call_retention on research_synthesis_call_permit(created_at,task_id);
