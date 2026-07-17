-- Deterministic digest of every durable Research Agent row in the MA4G run.
-- Empty-component sentinels and binary ordering make absence/order observable.

set session group_concat_max_len = 16777216;
set @ma4g_run_id = '00000000-0000-0000-0000-00000000a002';

drop temporary table if exists ma4g_state_components;
create temporary table ma4g_state_components(component varchar(96) primary key);
insert into ma4g_state_components(component) values
    ('research_run'),
    ('research_trace'),
    ('research_row'),
    ('research_cell'),
    ('research_execution_checkpoint'),
    ('source_evidence'),
    ('research_cell_evidence'),
    ('research_agent_candidate'),
    ('research_cell_merge'),
    ('research_agent_task'),
    ('research_agent_execution'),
    ('research_agent_completion'),
    ('research_budget_reservation'),
    ('research_agent_checkpoint'),
    ('research_agent_outbox'),
    ('research_agent_delivery_failure');

drop temporary table if exists ma4g_state_rows;
create temporary table ma4g_state_rows (
    component varchar(96) not null,
    row_key varchar(512) not null,
    row_digest char(64) not null,
    primary key(component, row_key)
);

insert into ma4g_state_rows
select 'research_run', id, sha2(concat('ma4g-state-row.v1\nresearch_run\n', cast(json_array(
    id, workspace_id, task_id, question, profile_key, context_snapshot_id, source_scope_json,
    control_pack_json, status, final_report_title, final_report_markdown, trace_summary,
    created_at, updated_at, report_source_id, resumed_from_research_run_id,
    resumed_from_checkpoint_no, research_intent_json, agent_execution_mode
) as char)), 256)
from research_run where id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_trace', id, sha2(concat('ma4g-state-row.v1\nresearch_trace\n', cast(json_array(
    id, research_run_id, trace_type, trace_message, payload_json, created_at
) as char)), 256)
from research_trace where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_row', id, sha2(concat('ma4g-state-row.v1\nresearch_row\n', cast(json_array(
    id, research_run_id, row_key, branch_id, source_id, source_title, search_query, read_focus,
    evidence_id, row_status, relation_type, support_score, conflict_score, support_level,
    verification_status, verifier_note, repair_hint, created_at, updated_at
) as char)), 256)
from research_row where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_cell', id, sha2(concat('ma4g-state-row.v1\nresearch_cell\n', cast(json_array(
    id, research_run_id, research_row_id, cell_key, branch_id, column_key, candidate_value,
    cell_status, confidence_score, confidence_score_ppm, evidence_refs_json,
    last_verifier_decision, repair_count, created_at, updated_at, cell_version,
    plan_revision, entity_set_version, last_merge_id, active_task_id, lease_epoch, fencing_token
) as char)), 256)
from research_cell where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_execution_checkpoint', id,
       sha2(concat('ma4g-state-row.v1\nresearch_execution_checkpoint\n', cast(json_array(
           id, research_run_id, checkpoint_no, snapshot_type, object_key, payload_sha256,
           content_size, active_branch_key, final_loop_decision, summary_json, created_at
       ) as char)), 256)
from research_execution_checkpoint where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'source_evidence', id, sha2(concat('ma4g-state-row.v1\nsource_evidence\n', cast(json_array(
    id, research_run_id, evidence_key, window_id, source_id, source_title, source_url,
    provider, adapter, search_query, read_focus, quote_text, claim_text, relation_type,
    support_score, conflict_score, support_score_ppm, conflict_score_ppm, snapshot_status,
    snapshot_key, agent_completion_id, content_digest, created_at
) as char)), 256)
from source_evidence where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_cell_evidence', id,
       sha2(concat('ma4g-state-row.v1\nresearch_cell_evidence\n', cast(json_array(
           id, research_run_id, research_cell_id, source_evidence_id, evidence_key,
           agent_completion_id, content_digest, created_at
       ) as char)), 256)
from research_cell_evidence where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_agent_candidate', id,
       sha2(concat('ma4g-state-row.v1\nresearch_agent_candidate\n', cast(json_array(
           id, research_run_id, task_id, execution_id, research_agent_execution_id,
           idempotency_key, cell_key, base_cell_version, plan_revision, entity_set_version,
           lease_epoch, fencing_token, candidate_value, evidence_ids_json, confidence_score,
           confidence_score_ppm, agent_completion_id, content_digest, submitted_at
       ) as char)), 256)
from research_agent_candidate where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_cell_merge', id,
       sha2(concat('ma4g-state-row.v1\nresearch_cell_merge\n', cast(json_array(
           id, research_run_id, candidate_id, merge_key, cell_key, expected_cell_version,
           result_cell_version, verdict, decision, reason_code, accepted_evidence_ids_json,
           agent_completion_id, content_digest, merged_at
       ) as char)), 256)
from research_cell_merge where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_agent_task', id,
       sha2(concat('ma4g-state-row.v1\nresearch_agent_task\n', cast(json_array(
           id, research_run_id, task_key, idempotency_key, wave_no, role, entity_id,
           branch_id, plan_revision, entity_set_version, target_cells_json, budget_json,
           status, lease_epoch, fencing_token, worker_instance_id, lease_expires_at,
           attempt_count, terminal_at, created_at, updated_at, next_attempt_at, max_attempts,
           terminal_reason, cancelled_at, target_bindings_json, execution_context_json,
           snapshot_schema_version, snapshot_digest
       ) as char)), 256)
from research_agent_task where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_agent_execution', e.id,
       sha2(concat('ma4g-state-row.v1\nresearch_agent_execution\n', cast(json_array(
           e.id, e.research_agent_task_id, e.execution_key, e.lease_epoch, e.fencing_token,
           e.worker_instance_id, e.status, e.termination_reason, e.usage_json,
           e.trace_digest, e.submitted_at
       ) as char)), 256)
from research_agent_execution e
join research_agent_task t on t.id = e.research_agent_task_id
where t.research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_agent_completion', c.id,
       sha2(concat('ma4g-state-row.v1\nresearch_agent_completion\n', cast(json_array(
           c.id, c.research_agent_task_id, c.execution_id, c.completion_key, c.schema_version,
           c.envelope_digest, c.envelope_json, c.envelope_size_bytes, c.snapshot_digest,
           c.worker_instance_id, c.lease_epoch, c.fencing_token, c.receipt_json,
           c.receipt_digest, c.committed_at
       ) as char)), 256)
from research_agent_completion c
join research_agent_task t on t.id = c.research_agent_task_id
where t.research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_budget_reservation', id,
       sha2(concat('ma4g-state-row.v1\nresearch_budget_reservation\n', cast(json_array(
           id, research_run_id, research_agent_task_id, idempotency_key, reserved_json,
           consumed_json, released_json, state, agent_completion_id, settlement_key,
           finalized_at, created_at, settled_at, updated_at
       ) as char)), 256)
from research_budget_reservation where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_agent_checkpoint', id,
       sha2(concat('ma4g-state-row.v1\nresearch_agent_checkpoint\n', cast(json_array(
           id, research_run_id, checkpoint_seq, wave_no, round_no, plan_revision,
           entity_set_version, ledger_hash, task_high_water_mark, candidate_high_water_mark,
           merge_high_water_mark, budget_summary_json, summary_json, created_at
       ) as char)), 256)
from research_agent_checkpoint where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_agent_outbox', id,
       sha2(concat('ma4g-state-row.v1\nresearch_agent_outbox\n', cast(json_array(
           id, research_run_id, research_agent_task_id, topic, message_key, payload_json,
           status, sent_at, created_at, updated_at, delivery_no
       ) as char)), 256)
from research_agent_outbox where research_run_id = @ma4g_run_id;

insert into ma4g_state_rows
select 'research_agent_delivery_failure', id,
       sha2(concat('ma4g-state-row.v1\nresearch_agent_delivery_failure\n', cast(json_array(
           id, research_run_id, research_agent_task_id, research_agent_outbox_id,
           failure_key, reason_code, trace_digest, delivery_attempt, redrive_status,
           created_at, redriven_at
       ) as char)), 256)
from research_agent_delivery_failure where research_run_id = @ma4g_run_id;

drop temporary table if exists ma4g_state_summary;
create temporary table ma4g_state_summary as
select c.component,
       count(r.row_key) as row_count,
       sha2(concat('ma4g-state-component.v1\n', c.component, '\n', coalesce(
           group_concat(
               concat(octet_length(r.row_key), ':', r.row_key, ':', r.row_digest)
               order by cast(r.row_key as binary) separator '|'
           ), 'EMPTY')), 256) as component_digest
from ma4g_state_components c
left join ma4g_state_rows r on r.component = c.component
group by c.component;

select 'COMPONENT', component, row_count, component_digest
from ma4g_state_summary order by cast(component as binary);

select 'OVERALL', '__OVERALL__', sum(row_count),
       sha2(concat('ma4g-state-overall.v1\n', group_concat(
           concat(octet_length(component), ':', component, ':', row_count, ':', component_digest)
           order by cast(component as binary) separator '|'
       )), 256)
from ma4g_state_summary;

