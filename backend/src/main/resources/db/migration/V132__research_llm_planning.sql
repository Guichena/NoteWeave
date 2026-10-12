-- 研究表格由 PLANNING 角色调用模型规划。列的含义（标签、描述、答案格式、检索意图）单独成表，
-- 深挖任务按列生成检索词时从这里读取。
create table research_column (
    id varchar(36) primary key,
    research_run_id varchar(36) not null,
    column_key varchar(160) not null,
    label varchar(500) not null,
    description longtext null,
    answer_format varchar(32) not null default 'SHORT_TEXT',
    high_risk boolean not null default false,
    required_provenance_level varchar(48) not null default 'QUALIFIED_SOURCE',
    search_intents_json json null,
    plan_revision int not null,
    created_at timestamp not null default current_timestamp,
    constraint fk_research_column_run foreign key (research_run_id) references research_run(id)
);

create unique index uq_research_column_key on research_column(research_run_id, column_key);

-- 规划状态：空值表示不经过规划阶段（旧 Run 或断点恢复）；PLANNING 等待规划任务；
-- REVIEW 等待用户确认；READY 可以下发深挖任务。
alter table research_run add column plan_status varchar(16) null;
alter table research_run add column plan_review_deadline timestamp null;
-- 规划阶段由模型生成的研究简报：目标、范围、时间范围、排除项、交付格式
alter table research_run add column research_brief_json json null;
