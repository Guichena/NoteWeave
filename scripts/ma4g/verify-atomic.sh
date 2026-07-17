#!/bin/sh
set -eu

expected_tasks="${1:-}"
expected_cells="${2:-}"
expected_workers="${3:-1}"
for value in "$expected_tasks" "$expected_cells" "$expected_workers"; do
  if ! printf '%s' "$value" | grep -Eq '^[1-9][0-9]*$'; then
    echo "usage: verify-atomic.sh EXPECTED_TASKS EXPECTED_CELLS [EXPECTED_WORKERS]" >&2
    exit 2
  fi
done

export MYSQL_PWD="${MYSQL_PWD:-noteweave123}"
mysql_value() { mysql -hmysql -unoteweave --batch --raw --skip-column-names noteweave -e "$1"; }
run_id="00000000-0000-0000-0000-00000000a002"

assert_eq() {
  label="$1"; expected="$2"; actual="$3"
  if [ "$actual" != "$expected" ]; then
    echo "MA4G_ATOMIC_ASSERTION_FAILED $label expected=$expected actual=$actual" >&2
    exit 1
  fi
  echo "MA4G_ATOMIC_ASSERTION_OK $label=$actual"
}

for attempt in $(seq 1 180); do
  submitted="$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id' and status='SUBMITTED'")"
  [ "$submitted" = "$expected_tasks" ] && break
  sleep 1
done

assert_eq task_count "$expected_tasks" "$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id'")"
assert_eq submitted_task_count "$expected_tasks" "$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id' and status='SUBMITTED'")"
assert_eq execution_count "$expected_tasks" "$(mysql_value "select count(*) from research_agent_execution e join research_agent_task t on t.id=e.research_agent_task_id where t.research_run_id='$run_id'")"
assert_eq completion_count "$expected_tasks" "$(mysql_value "select count(*) from research_agent_completion c join research_agent_task t on t.id=c.research_agent_task_id where t.research_run_id='$run_id'")"
assert_eq one_execution_per_task "$expected_tasks" "$(mysql_value "select count(*) from (select e.research_agent_task_id from research_agent_execution e join research_agent_task t on t.id=e.research_agent_task_id where t.research_run_id='$run_id' group by e.research_agent_task_id having count(*)=1) x")"
assert_eq one_completion_per_task "$expected_tasks" "$(mysql_value "select count(*) from (select c.research_agent_task_id from research_agent_completion c join research_agent_task t on t.id=c.research_agent_task_id where t.research_run_id='$run_id' group by c.research_agent_task_id having count(*)=1) x")"
assert_eq completion_execution_identity "$expected_tasks" "$(mysql_value "select count(*) from research_agent_completion c join research_agent_execution e on e.id=c.execution_id and e.research_agent_task_id=c.research_agent_task_id join research_agent_task t on t.id=c.research_agent_task_id where t.research_run_id='$run_id' and c.completion_key=e.execution_key and c.lease_epoch=e.lease_epoch and c.fencing_token=e.fencing_token and c.worker_instance_id=e.worker_instance_id")"

assert_eq evidence_count "$expected_cells" "$(mysql_value "select count(*) from source_evidence where research_run_id='$run_id'")"
assert_eq candidate_count "$expected_cells" "$(mysql_value "select count(*) from research_agent_candidate where research_run_id='$run_id'")"
assert_eq accepted_merge_count "$expected_cells" "$(mysql_value "select count(*) from research_cell_merge where research_run_id='$run_id' and decision='ACCEPTED'")"
assert_eq rejected_merge_count 0 "$(mysql_value "select count(*) from research_cell_merge where research_run_id='$run_id' and decision<>'ACCEPTED'")"
assert_eq cell_evidence_count "$expected_cells" "$(mysql_value "select count(*) from research_cell_evidence where research_run_id='$run_id'")"
assert_eq completion_bound_evidence "$expected_cells" "$(mysql_value "select count(*) from source_evidence where research_run_id='$run_id' and agent_completion_id is not null and content_digest regexp '^sha256:[0-9a-f]{64}$'")"
assert_eq completion_bound_candidates "$expected_cells" "$(mysql_value "select count(*) from research_agent_candidate where research_run_id='$run_id' and agent_completion_id is not null and research_agent_execution_id is not null and content_digest regexp '^sha256:[0-9a-f]{64}$'")"
assert_eq completion_bound_merges "$expected_cells" "$(mysql_value "select count(*) from research_cell_merge where research_run_id='$run_id' and agent_completion_id is not null and content_digest regexp '^sha256:[0-9a-f]{64}$'")"
assert_eq completion_bound_lineage "$expected_cells" "$(mysql_value "select count(*) from research_cell_evidence where research_run_id='$run_id' and agent_completion_id is not null and content_digest regexp '^sha256:[0-9a-f]{64}$'")"
assert_eq lineage_same_completion "$expected_cells" "$(mysql_value "select count(*) from research_cell_evidence rce join source_evidence se on se.id=rce.source_evidence_id and se.agent_completion_id=rce.agent_completion_id join research_agent_candidate c on c.research_run_id=rce.research_run_id and c.agent_completion_id=rce.agent_completion_id and json_contains(c.evidence_ids_json, json_quote(rce.evidence_key)) join research_cell_merge m on m.candidate_id=c.id and m.agent_completion_id=rce.agent_completion_id and m.cell_key=(select cell_key from research_cell where id=rce.research_cell_id) where rce.research_run_id='$run_id' and m.decision='ACCEPTED'")"

assert_eq verified_cells "$expected_cells" "$(mysql_value "select count(*) from research_cell where research_run_id='$run_id' and cell_status='VERIFIED' and cell_version=1 and active_task_id is null")"
assert_eq exact_cell_ppm "$expected_cells" "$(mysql_value "select count(*) from research_cell where research_run_id='$run_id' and confidence_score_ppm=800000")"
assert_eq exact_candidate_ppm "$expected_cells" "$(mysql_value "select count(*) from research_agent_candidate where research_run_id='$run_id' and confidence_score_ppm=800000")"
assert_eq exact_evidence_ppm "$expected_cells" "$(mysql_value "select count(*) from source_evidence where research_run_id='$run_id' and support_score_ppm=800000 and conflict_score_ppm=0")"

assert_eq valid_completion_contracts "$expected_tasks" "$(mysql_value "select count(*) from research_agent_completion c join research_agent_task t on t.id=c.research_agent_task_id where t.research_run_id='$run_id' and c.schema_version='research-agent-completion.v1' and c.envelope_digest regexp '^sha256:[0-9a-f]{64}$' and c.receipt_digest regexp '^sha256:[0-9a-f]{64}$' and c.envelope_size_bytes=octet_length(c.envelope_json) and json_unquote(json_extract(c.envelope_json,'$.envelope_digest'))=c.envelope_digest and json_unquote(json_extract(c.receipt_json,'$.receipt_digest'))=c.receipt_digest")"
assert_eq active_workers "$expected_workers" "$(mysql_value "select count(distinct e.worker_instance_id) from research_agent_execution e join research_agent_task t on t.id=e.research_agent_task_id where t.research_run_id='$run_id'")"

assert_eq settled_budgets "$expected_tasks" "$(mysql_value "select count(*) from research_budget_reservation where research_run_id='$run_id' and state='SETTLED' and agent_completion_id is not null and settlement_key is not null and finalized_at is not null")"
budget_violations="$(mysql_value "
select count(*) from research_budget_reservation r
where r.research_run_id='$run_id' and (
  json_length(r.reserved_json)<>10 or json_length(r.consumed_json)<>10 or json_length(r.released_json)<>10
  or json_contains_path(r.reserved_json,'all','$.llm_calls','$.search_calls','$.fetch_calls','$.read_calls','$.extract_calls','$.evidence_cards','$.evidence_appended','$.candidates_submitted','$.candidate_merges_accepted','$.candidate_merges_rejected')=0
  or json_contains_path(r.consumed_json,'all','$.llm_calls','$.search_calls','$.fetch_calls','$.read_calls','$.extract_calls','$.evidence_cards','$.evidence_appended','$.candidates_submitted','$.candidate_merges_accepted','$.candidate_merges_rejected')=0
  or json_contains_path(r.released_json,'all','$.llm_calls','$.search_calls','$.fetch_calls','$.read_calls','$.extract_calls','$.evidence_cards','$.evidence_appended','$.candidates_submitted','$.candidate_merges_accepted','$.candidate_merges_rejected')=0
  or exists (
    select 1 from json_table(json_keys(r.reserved_json), '\$[*]' columns(dimension varchar(64) path '\$')) dims
    where cast(json_unquote(json_extract(r.reserved_json, concat('\$.',dims.dimension))) as unsigned)
      <> cast(json_unquote(json_extract(r.consumed_json, concat('\$.',dims.dimension))) as unsigned)
       + cast(json_unquote(json_extract(r.released_json, concat('\$.',dims.dimension))) as unsigned)
  )
)")"
assert_eq budget_conservation_violations 0 "$budget_violations"
assert_eq worker_and_derived_budget_counts "$expected_tasks" "$(mysql_value "select count(*) from research_budget_reservation r where r.research_run_id='$run_id' and cast(json_unquote(json_extract(r.consumed_json,'$.llm_calls')) as unsigned)=0 and cast(json_unquote(json_extract(r.consumed_json,'$.search_calls')) as unsigned)=1 and cast(json_unquote(json_extract(r.consumed_json,'$.fetch_calls')) as unsigned)=1 and cast(json_unquote(json_extract(r.consumed_json,'$.read_calls')) as unsigned)=1 and cast(json_unquote(json_extract(r.consumed_json,'$.extract_calls')) as unsigned)=1 and cast(json_unquote(json_extract(r.consumed_json,'$.evidence_cards')) as unsigned)=2 and cast(json_unquote(json_extract(r.consumed_json,'$.evidence_appended')) as unsigned)=2 and cast(json_unquote(json_extract(r.consumed_json,'$.candidates_submitted')) as unsigned)=2 and cast(json_unquote(json_extract(r.consumed_json,'$.candidate_merges_accepted')) as unsigned)=2 and cast(json_unquote(json_extract(r.consumed_json,'$.candidate_merges_rejected')) as unsigned)=0")"

assert_eq sent_outboxes "$expected_tasks" "$(mysql_value "select count(*) from research_agent_outbox where research_run_id='$run_id' and status='SENT'")"
assert_eq ready_outboxes 0 "$(mysql_value "select count(*) from research_agent_outbox where research_run_id='$run_id' and status='READY'")"
assert_eq delivery_failures 0 "$(mysql_value "select count(*) from research_agent_delivery_failure where research_run_id='$run_id'")"

mysql -hmysql -unoteweave noteweave -e "
select t.id,t.status,t.lease_epoch,t.fencing_token,t.attempt_count,t.worker_instance_id,
       c.id completion_id,c.envelope_digest,c.receipt_digest,c.envelope_size_bytes
from research_agent_task t join research_agent_completion c on c.research_agent_task_id=t.id
where t.research_run_id='$run_id' order by t.id;
select cell_key,cell_status,cell_version,confidence_score_ppm,active_task_id
from research_cell where research_run_id='$run_id' order by cast(cell_key as binary);
select state,reserved_json,consumed_json,released_json,settlement_key
from research_budget_reservation where research_run_id='$run_id' order by id;
"

echo "MA4G_ATOMIC_AGGREGATE_VERIFIED tasks=$expected_tasks cells=$expected_cells workers=$expected_workers"
