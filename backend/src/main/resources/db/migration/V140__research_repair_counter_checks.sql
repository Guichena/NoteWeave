-- 查询历史与反证结果随任务完成事务保存，任务重放不会重复写入。
create table research_task_query (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    task_id varchar(36) not null,
    cell_key varchar(160) not null,
    query_text varchar(200) not null,
    strategy varchar(32) not null,
    created_at timestamp not null default current_timestamp
);
create index idx_research_task_query_cell on research_task_query(research_run_id, cell_key);

create table research_counter_check (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    task_id varchar(36) not null,
    cell_key varchar(160) not null,
    original_claim longtext not null,
    claim_text longtext not null,
    verdict varchar(32) not null,
    counter_query_count int not null,
    refuting_evidence_keys_json json not null,
    checked_at timestamp not null default current_timestamp
);
create index idx_research_counter_check_cell on research_counter_check(research_run_id, cell_key);

alter table research_agent_replan_audit add column repair_cause varchar(48) null;
alter table research_agent_replan_audit add column strategy varchar(32) null;
alter table research_agent_replan_audit add column query_count int not null default 0;

-- 正式结论只接受与最后一次合并候选绑定的反证，旧候选的结果不能复用。
create view research_counter_eligible_cell as
select rc.* from research_cell rc
where (rc.high_risk = false and not exists (
    select 1 from research_verifier_decision vd where vd.research_run_id = rc.research_run_id
        and vd.target_id = rc.cell_key and vd.decision_type = 'GLOBAL_CONFLICT'
)) or exists (
    select 1 from research_cell_merge cm
    join research_agent_candidate candidate on candidate.id = cm.candidate_id
    where (cm.merge_key = rc.last_merge_id or cm.id = rc.last_merge_id)
        and cm.research_run_id = rc.research_run_id and cm.cell_key = rc.cell_key
        and cm.result_cell_version = rc.cell_version and cm.decision = 'ACCEPTED'
        and candidate.candidate_value = rc.candidate_value
        and exists (select 1 from research_counter_check cc where cc.task_id = candidate.task_id
            and cc.research_run_id = rc.research_run_id and cc.cell_key = rc.cell_key and cc.original_claim = rc.candidate_value)
        and not exists (select 1 from research_counter_check cc where cc.task_id = candidate.task_id
            and cc.research_run_id = rc.research_run_id and cc.cell_key = rc.cell_key and cc.original_claim = rc.candidate_value
            and cc.verdict in ('NOT_RUN', 'REFUTED'))
);
