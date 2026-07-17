#!/bin/sh
set -eu

mode="${1:-}"
case "$mode" in fresh|legacy) ;; *) echo "usage: verify-migration.sh fresh|legacy" >&2; exit 2 ;; esac
host="${MA4G_MYSQL_HOST:-mysql}"
database="${MA4G_MYSQL_DATABASE:-noteweave}"
export MYSQL_PWD="${MYSQL_PWD:-noteweave123}"
mysql_value() { mysql -h"$host" -unoteweave --batch --raw --skip-column-names "$database" -e "$1"; }
assert_eq() {
  label="$1"; expected="$2"; actual="$3"
  if [ "$actual" != "$expected" ]; then
    echo "MA4G_MIGRATION_ASSERTION_FAILED mode=$mode $label expected=$expected actual=$actual" >&2
    exit 1
  fi
  echo "MA4G_MIGRATION_ASSERTION_OK mode=$mode $label=$actual"
}

assert_eq mysql_8_4 1 "$(mysql_value "select version() regexp '^8[.]4[.]'")"
assert_eq server_charset utf8mb4 "$(mysql_value "select @@character_set_server")"
assert_eq server_collation utf8mb4_unicode_ci "$(mysql_value "select @@collation_server")"
assert_eq database_charset utf8mb4 "$(mysql_value "select default_character_set_name from information_schema.schemata where schema_name=database()")"
assert_eq database_collation utf8mb4_unicode_ci "$(mysql_value "select default_collation_name from information_schema.schemata where schema_name=database()")"
assert_eq transaction_isolation READ-COMMITTED "$(mysql_value "select @@transaction_isolation")"
assert_eq flyway_first_version 1 "$(mysql_value "select min(cast(version as unsigned)) from flyway_schema_history where success=1")"
assert_eq flyway_v045_success 1 "$(mysql_value "select count(*) from flyway_schema_history where cast(version as unsigned)=45 and success=1")"
if [ "$mode" = "legacy" ]; then
  assert_eq flyway_latest_version 45 "$(mysql_value "select max(cast(version as unsigned)) from flyway_schema_history where success=1")"
else
  assert_eq flyway_latest_at_least_v045 1 "$(mysql_value "select coalesce(max(cast(version as unsigned)) >= 45,0) from flyway_schema_history where success=1")"
fi
assert_eq flyway_failures 0 "$(mysql_value "select count(*) from flyway_schema_history where success<>1")"
assert_eq completion_table 1 "$(mysql_value "select count(*) from information_schema.tables where table_schema=database() and table_name='research_agent_completion' and table_collation='utf8mb4_unicode_ci'")"
assert_eq completion_required_columns 15 "$(mysql_value "select count(*) from information_schema.columns where table_schema=database() and table_name='research_agent_completion' and column_name in ('id','research_agent_task_id','execution_id','completion_key','schema_version','envelope_digest','envelope_json','envelope_size_bytes','snapshot_digest','worker_instance_id','lease_epoch','fencing_token','receipt_json','receipt_digest','committed_at')")"
assert_eq exact_ppm_columns 4 "$(mysql_value "select count(*) from information_schema.columns where table_schema=database() and ((table_name='source_evidence' and column_name in ('support_score_ppm','conflict_score_ppm')) or (table_name='research_agent_candidate' and column_name='confidence_score_ppm') or (table_name='research_cell' and column_name='confidence_score_ppm')) and data_type='int' and is_nullable='YES'")"
assert_eq completion_provenance_columns 12 "$(mysql_value "select count(*) from information_schema.columns where table_schema=database() and ((table_name='source_evidence' and column_name in ('agent_completion_id','content_digest')) or (table_name='research_agent_candidate' and column_name in ('agent_completion_id','content_digest','research_agent_execution_id')) or (table_name='research_cell_merge' and column_name in ('agent_completion_id','content_digest')) or (table_name='research_cell_evidence' and column_name in ('agent_completion_id','content_digest')) or (table_name='research_budget_reservation' and column_name in ('agent_completion_id','settlement_key','finalized_at')))")"
assert_eq completion_unique_indexes 3 "$(mysql_value "select count(distinct index_name) from information_schema.statistics where table_schema=database() and table_name='research_agent_completion' and non_unique=0 and index_name in ('uq_research_agent_completion_task','uq_research_agent_completion_execution','uq_research_agent_completion_task_key')")"
assert_eq completion_digest_index 1 "$(mysql_value "select count(distinct index_name) from information_schema.statistics where table_schema=database() and table_name='research_agent_completion' and index_name='idx_research_agent_completion_digest' and non_unique=1")"
assert_eq completion_foreign_keys 8 "$(mysql_value "select count(*) from information_schema.referential_constraints where constraint_schema=database() and constraint_name in ('fk_research_agent_completion_task','fk_research_agent_completion_execution','fk_source_evidence_agent_completion','fk_research_agent_candidate_completion','fk_research_agent_candidate_execution','fk_research_cell_merge_completion','fk_research_cell_evidence_completion','fk_research_budget_agent_completion')")"
assert_eq completion_child_indexes 6 "$(mysql_value "select count(*) from (select table_name,index_name,group_concat(column_name order by seq_in_index) columns_in_order from information_schema.statistics where table_schema=database() and index_name in ('idx_source_evidence_agent_completion','idx_research_agent_candidate_completion','idx_research_agent_candidate_execution','idx_research_cell_merge_completion','idx_research_cell_evidence_completion','idx_research_budget_agent_completion') group by table_name,index_name having (index_name='idx_source_evidence_agent_completion' and columns_in_order='agent_completion_id,evidence_key') or (index_name='idx_research_agent_candidate_completion' and columns_in_order='agent_completion_id,idempotency_key') or (index_name='idx_research_agent_candidate_execution' and columns_in_order='research_agent_execution_id') or (index_name='idx_research_cell_merge_completion' and columns_in_order='agent_completion_id,merge_key') or (index_name='idx_research_cell_evidence_completion' and columns_in_order='agent_completion_id,evidence_key') or (index_name='idx_research_budget_agent_completion' and columns_in_order='agent_completion_id')) verified_indexes")"
assert_eq completion_key_length 160 "$(mysql_value "select character_maximum_length from information_schema.columns where table_schema=database() and table_name='research_agent_completion' and column_name='completion_key'")"
assert_eq worker_id_length 128 "$(mysql_value "select character_maximum_length from information_schema.columns where table_schema=database() and table_name='research_agent_completion' and column_name='worker_instance_id'")"
assert_eq task_id_length 36 "$(mysql_value "select character_maximum_length from information_schema.columns where table_schema=database() and table_name='research_agent_task' and column_name='id'")"
assert_eq source_key_lengths '64:64:64:300' "$(mysql_value "select group_concat(character_maximum_length order by field(column_name,'evidence_key','window_id','source_id','source_title') separator ':') from information_schema.columns where table_schema=database() and table_name='source_evidence' and column_name in ('evidence_key','window_id','source_id','source_title')")"
assert_eq candidate_cell_key_length 160 "$(mysql_value "select character_maximum_length from information_schema.columns where table_schema=database() and table_name='research_agent_candidate' and column_name='cell_key'")"
assert_eq candidate_value_longtext longtext "$(mysql_value "select data_type from information_schema.columns where table_schema=database() and table_name='research_agent_candidate' and column_name='candidate_value'")"

probe="$(mysql_value "drop temporary table if exists ma4g_collation_probe; create temporary table ma4g_collation_probe(k varchar(64) collate utf8mb4_unicode_ci unique); insert into ma4g_collation_probe values('resume'); insert ignore into ma4g_collation_probe values(_utf8mb4'Résumé'); select count(*) from ma4g_collation_probe;")"
assert_eq unicode_ci_collision_probe 1 "$probe"

if [ "$mode" = "legacy" ]; then
  assert_eq legacy_evidence_decimal '0.8765:0.0123' "$(mysql_value "select concat(cast(support_score as char),':',cast(conflict_score as char)) from source_evidence where id='00000000-0000-0000-0000-00000000c010'")"
  assert_eq legacy_candidate_decimal 0.4321 "$(mysql_value "select cast(confidence_score as char) from research_agent_candidate where id='00000000-0000-0000-0000-00000000c011'")"
  assert_eq legacy_cell_decimal 0.5432 "$(mysql_value "select cast(confidence_score as char) from research_cell where id='00000000-0000-0000-0000-00000000c00a'")"
  assert_eq legacy_ppm_and_provenance_null 1 "$(mysql_value "select count(*) from source_evidence se join research_agent_candidate c on c.id='00000000-0000-0000-0000-00000000c011' join research_cell rc on rc.id='00000000-0000-0000-0000-00000000c00a' where se.id='00000000-0000-0000-0000-00000000c010' and se.support_score_ppm is null and se.conflict_score_ppm is null and se.agent_completion_id is null and se.content_digest is null and c.confidence_score_ppm is null and c.agent_completion_id is null and c.content_digest is null and c.research_agent_execution_id is null and rc.confidence_score_ppm is null")"
  assert_eq legacy_merge_lineage_budget_preserved 1 "$(mysql_value "select count(*) from research_cell_merge m join research_cell_evidence ce on ce.id='00000000-0000-0000-0000-00000000c014' join research_budget_reservation b on b.id='00000000-0000-0000-0000-00000000c015' where m.id='00000000-0000-0000-0000-00000000c013' and m.candidate_id='00000000-0000-0000-0000-00000000c011' and m.merge_key='legacy-merge' and m.decision='ACCEPTED' and m.accepted_evidence_ids_json='[\"legacy-evidence\"]' and m.agent_completion_id is null and m.content_digest is null and ce.research_cell_id='00000000-0000-0000-0000-00000000c00a' and ce.source_evidence_id='00000000-0000-0000-0000-00000000c010' and ce.evidence_key='legacy-evidence' and ce.agent_completion_id is null and ce.content_digest is null and b.research_agent_task_id='00000000-0000-0000-0000-00000000c012' and b.state='RESERVED' and b.reserved_json='{\"llm_calls\":2}' and b.consumed_json='{\"llm_calls\":0}' and b.released_json='{\"llm_calls\":0}' and b.agent_completion_id is null and b.settlement_key is null and b.finalized_at is null")"
  assert_eq legacy_completion_count 0 "$(mysql_value "select count(*) from research_agent_completion")"
fi

mysql -h"$host" -unoteweave "$database" -e "
select version,description,type,installed_on,success from flyway_schema_history order by installed_rank;
select table_name,column_name,column_type,is_nullable,character_maximum_length,collation_name
from information_schema.columns
where table_schema=database() and (table_name='research_agent_completion' or column_name like '%_ppm' or column_name in ('agent_completion_id','content_digest','settlement_key','finalized_at','research_agent_execution_id'))
order by table_name,ordinal_position;
select table_name,index_name,non_unique,seq_in_index,column_name
from information_schema.statistics where table_schema=database() and (table_name='research_agent_completion' or index_name like '%agent_completion%')
order by table_name,index_name,seq_in_index;
select constraint_name,table_name,referenced_table_name
from information_schema.referential_constraints
where constraint_schema=database() and constraint_name in
('fk_research_agent_completion_task','fk_research_agent_completion_execution','fk_source_evidence_agent_completion',
 'fk_research_agent_candidate_completion','fk_research_agent_candidate_execution','fk_research_cell_merge_completion',
 'fk_research_cell_evidence_completion','fk_research_budget_agent_completion')
order by table_name,constraint_name;
explain select * from research_agent_completion where research_agent_task_id='00000000-0000-0000-0000-000000000000';
explain select * from research_agent_completion where research_agent_task_id='00000000-0000-0000-0000-000000000000' and completion_key='deep-cell:probe';
explain select * from source_evidence where agent_completion_id='00000000-0000-0000-0000-000000000000' and evidence_key='evidence:probe';
explain select * from research_agent_candidate where agent_completion_id='00000000-0000-0000-0000-000000000000' and idempotency_key='candidate:probe';
explain select * from research_agent_candidate where research_agent_execution_id='00000000-0000-0000-0000-000000000000';
explain select * from research_cell_merge where agent_completion_id='00000000-0000-0000-0000-000000000000' and merge_key='merge:probe';
explain select * from research_cell_evidence where agent_completion_id='00000000-0000-0000-0000-000000000000' and evidence_key='evidence:probe';
explain select * from research_budget_reservation where agent_completion_id='00000000-0000-0000-0000-000000000000';
"
echo "MA4G_MIGRATION_VERIFIED mode=$mode"
