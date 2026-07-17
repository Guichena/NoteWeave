#!/bin/sh
set -eu
export MYSQL_PWD="${MYSQL_PWD:-noteweave123}"
mysql -hmysql -unoteweave noteweave -e "
select version() mysql_version, @@character_set_server character_set_server,
       @@collation_server collation_server, @@transaction_isolation transaction_isolation;
select version,description,type,installed_on,success from flyway_schema_history order by installed_rank;
select table_name,column_name,column_type,is_nullable,character_maximum_length,collation_name
from information_schema.columns
where table_schema=database() and (table_name='research_agent_completion' or column_name like '%_ppm' or column_name in ('agent_completion_id','content_digest','settlement_key','finalized_at','research_agent_execution_id'))
order by table_name,ordinal_position;
select table_name,index_name,non_unique,seq_in_index,column_name
from information_schema.statistics
where table_schema=database() and (table_name='research_agent_completion' or index_name like '%agent_completion%')
order by table_name,index_name,seq_in_index;
select constraint_name,table_name,referenced_table_name
from information_schema.referential_constraints
where constraint_schema=database() and (constraint_name like '%completion%' or table_name='research_agent_completion')
order by table_name,constraint_name;
select id,status,lease_epoch,fencing_token,attempt_count,worker_instance_id,snapshot_digest,
       target_bindings_json,budget_json from research_agent_task
where research_run_id='00000000-0000-0000-0000-00000000a002' order by id;
select c.id,c.research_agent_task_id,c.execution_id,c.completion_key,c.envelope_digest,
       c.envelope_size_bytes,c.receipt_digest,c.envelope_json,c.receipt_json
from research_agent_completion c join research_agent_task t on t.id=c.research_agent_task_id
where t.research_run_id='00000000-0000-0000-0000-00000000a002' order by c.completion_key;
select id,evidence_key,agent_completion_id,support_score_ppm,conflict_score_ppm,content_digest
from source_evidence where research_run_id='00000000-0000-0000-0000-00000000a002' order by evidence_key;
select id,idempotency_key,cell_key,research_agent_execution_id,agent_completion_id,
       confidence_score_ppm,content_digest from research_agent_candidate
where research_run_id='00000000-0000-0000-0000-00000000a002' order by cell_key;
select id,merge_key,cell_key,decision,result_cell_version,agent_completion_id,content_digest
from research_cell_merge where research_run_id='00000000-0000-0000-0000-00000000a002' order by cell_key;
select id,research_cell_id,source_evidence_id,evidence_key,agent_completion_id,content_digest
from research_cell_evidence where research_run_id='00000000-0000-0000-0000-00000000a002' order by evidence_key;
select cell_key,cell_status,cell_version,confidence_score_ppm,evidence_refs_json,active_task_id
from research_cell where research_run_id='00000000-0000-0000-0000-00000000a002' order by cast(cell_key as binary);
select id,state,reserved_json,consumed_json,released_json,agent_completion_id,settlement_key,finalized_at
from research_budget_reservation where research_run_id='00000000-0000-0000-0000-00000000a002' order by id;
select id,status,delivery_no,message_key,payload_json from research_agent_outbox
where research_run_id='00000000-0000-0000-0000-00000000a002' order by id;
"

