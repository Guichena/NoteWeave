-- 一次来源可产生多条事件；评分/编辑仍允许替换历史，不进入此收据表。
create table memory_feedback_receipt (
    workspace_id varchar(36) not null,
    signal_kind varchar(32) not null,
    source_ref varchar(160) not null,
    created_at timestamp not null default current_timestamp,
    primary key (workspace_id, signal_kind, source_ref)
);

-- 历史有效与已替代事件均代表来源已经处理；保留所有原事件和效用，不猜测补偿。
insert into memory_feedback_receipt(workspace_id, signal_kind, source_ref)
select distinct workspace_id, signal_kind, source_ref
from memory_application_outcome
where signal_kind in ('RETRY', 'FOLLOW_UP') and source_ref is not null;
