#!/bin/sh
set -eu

mysql_value() {
  mysql -hmysql -unoteweave -pnoteweave123 -Nse "$1" noteweave
}

run_id="00000000-0000-0000-0000-00000000f002"
for attempt in $(seq 1 90); do
  status="$(mysql_value "select coalesce(max(status), '') from research_agent_task where research_run_id='$run_id'")"
  if [ "$status" = "SUBMITTED" ]; then break; fi
  sleep 1
done

assert_eq() {
  label="$1"; expected="$2"; actual="$3"
  if [ "$actual" != "$expected" ]; then
    echo "MA4F_ASSERTION_FAILED $label expected=$expected actual=$actual" >&2
    exit 1
  fi
  echo "MA4F_ASSERTION_OK $label=$actual"
}

assert_eq task_count 1 "$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id'")"
assert_eq task_status SUBMITTED "$(mysql_value "select status from research_agent_task where research_run_id='$run_id'")"
assert_eq lease_epoch 2 "$(mysql_value "select lease_epoch from research_agent_task where research_run_id='$run_id'")"
assert_eq fencing_token 2 "$(mysql_value "select fencing_token from research_agent_task where research_run_id='$run_id'")"
assert_eq attempt_count 2 "$(mysql_value "select attempt_count from research_agent_task where research_run_id='$run_id'")"
assert_eq final_worker ma4f-worker-b "$(mysql_value "select worker_instance_id from research_agent_task where research_run_id='$run_id'")"
assert_eq execution_count 1 "$(mysql_value "select count(*) from research_agent_execution e join research_agent_task t on t.id=e.research_agent_task_id where t.research_run_id='$run_id'")"
assert_eq evidence_count 1 "$(mysql_value "select count(*) from source_evidence where research_run_id='$run_id'")"
assert_eq candidate_count 1 "$(mysql_value "select count(*) from research_agent_candidate where research_run_id='$run_id'")"
assert_eq accepted_merge_count 1 "$(mysql_value "select count(*) from research_cell_merge where research_run_id='$run_id' and decision='ACCEPTED'")"
assert_eq verified_cell_count 1 "$(mysql_value "select count(*) from research_cell where research_run_id='$run_id' and cell_status='VERIFIED' and cell_version=1 and active_task_id is null")"
assert_eq budget_state SETTLED "$(mysql_value "select state from research_budget_reservation where research_run_id='$run_id'")"
assert_eq outbox_delivery 2 "$(mysql_value "select delivery_no from research_agent_outbox where research_run_id='$run_id'")"
assert_eq outbox_status CANCELLED "$(mysql_value "select status from research_agent_outbox where research_run_id='$run_id'")"

echo "MA4F_CRASH_RECOVERY_VERIFIED"
