#!/bin/sh
set -eu

mysql_value() {
  mysql -hmysql -unoteweave -pnoteweave123 -Nse "$1" noteweave
}

for attempt in $(seq 1 90); do
  status="$(mysql_value "select coalesce(max(status), '') from research_agent_task where research_run_id='00000000-0000-0000-0000-00000000f002'")"
  if [ "$status" = "SUBMITTED" ]; then
    break
  fi
  sleep 1
done

assert_eq() {
  label="$1"
  expected="$2"
  actual="$3"
  if [ "$actual" != "$expected" ]; then
    echo "MA4F_ASSERTION_FAILED $label expected=$expected actual=$actual" >&2
    exit 1
  fi
  echo "MA4F_ASSERTION_OK $label=$actual"
}

run_id="00000000-0000-0000-0000-00000000f002"
assert_eq task_count 1 "$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id'")"
assert_eq task_status SUBMITTED "$(mysql_value "select status from research_agent_task where research_run_id='$run_id'")"
assert_eq execution_count 1 "$(mysql_value "select count(*) from research_agent_execution e join research_agent_task t on t.id=e.research_agent_task_id where t.research_run_id='$run_id'")"
assert_eq evidence_count 1 "$(mysql_value "select count(*) from source_evidence where research_run_id='$run_id'")"
assert_eq candidate_count 1 "$(mysql_value "select count(*) from research_agent_candidate where research_run_id='$run_id'")"
assert_eq merge_count 1 "$(mysql_value "select count(*) from research_cell_merge where research_run_id='$run_id' and decision='ACCEPTED'")"
assert_eq cell_status VERIFIED "$(mysql_value "select cell_status from research_cell where research_run_id='$run_id'")"
assert_eq cell_version 1 "$(mysql_value "select cell_version from research_cell where research_run_id='$run_id'")"
assert_eq cell_binding_released 0 "$(mysql_value "select count(*) from research_cell where research_run_id='$run_id' and active_task_id is not null")"
assert_eq budget_state SETTLED "$(mysql_value "select state from research_budget_reservation where research_run_id='$run_id'")"
assert_eq outbox_status SENT "$(mysql_value "select status from research_agent_outbox where research_run_id='$run_id'")"

mysql -hmysql -unoteweave -pnoteweave123 -e "
select id,status,lease_epoch,fencing_token,attempt_count,worker_instance_id from research_agent_task where research_run_id='$run_id';
select id,execution_key,lease_epoch,fencing_token,worker_instance_id,status,termination_reason,usage_json from research_agent_execution where research_agent_task_id in (select id from research_agent_task where research_run_id='$run_id');
select cell_key,cell_status,cell_version,last_merge_id,active_task_id from research_cell where research_run_id='$run_id';
select state,reserved_json,consumed_json,released_json from research_budget_reservation where research_run_id='$run_id';
" noteweave

echo "MA4F_BASELINE_VERIFIED"
