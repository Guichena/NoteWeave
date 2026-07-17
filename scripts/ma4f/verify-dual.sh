#!/bin/sh
set -eu

mysql_value() {
  mysql -hmysql -unoteweave -pnoteweave123 -Nse "$1" noteweave
}

run_id="00000000-0000-0000-0000-00000000f002"
for attempt in $(seq 1 120); do
  submitted="$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id' and status='SUBMITTED'")"
  if [ "$submitted" = "8" ]; then break; fi
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

assert_eq task_count 8 "$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id'")"
assert_eq submitted_tasks 8 "$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id' and status='SUBMITTED'")"
assert_eq execution_count 8 "$(mysql_value "select count(*) from research_agent_execution e join research_agent_task t on t.id=e.research_agent_task_id where t.research_run_id='$run_id'")"
assert_eq unique_task_executions 8 "$(mysql_value "select count(*) from (select research_agent_task_id from research_agent_execution group by research_agent_task_id having count(*)=1) x")"
assert_eq active_workers 2 "$(mysql_value "select count(distinct e.worker_instance_id) from research_agent_execution e join research_agent_task t on t.id=e.research_agent_task_id where t.research_run_id='$run_id'")"
assert_eq candidate_count 8 "$(mysql_value "select count(*) from research_agent_candidate where research_run_id='$run_id'")"
assert_eq accepted_merges 8 "$(mysql_value "select count(*) from research_cell_merge where research_run_id='$run_id' and decision='ACCEPTED'")"
assert_eq verified_cells 8 "$(mysql_value "select count(*) from research_cell where research_run_id='$run_id' and cell_status='VERIFIED' and cell_version=1")"
assert_eq active_cell_bindings 0 "$(mysql_value "select count(*) from research_cell where research_run_id='$run_id' and active_task_id is not null")"
assert_eq settled_budgets 8 "$(mysql_value "select count(*) from research_budget_reservation where research_run_id='$run_id' and state='SETTLED'")"
assert_eq sent_outboxes 8 "$(mysql_value "select count(*) from research_agent_outbox where research_run_id='$run_id' and status='SENT'")"
assert_eq grounded_evidence_rows 1 "$(mysql_value "select count(*) from source_evidence where research_run_id='$run_id'")"

mysql -hmysql -unoteweave -pnoteweave123 -e "
select e.worker_instance_id,count(*) as executions from research_agent_execution e join research_agent_task t on t.id=e.research_agent_task_id where t.research_run_id='$run_id' group by e.worker_instance_id;
select status,lease_epoch,fencing_token,attempt_count,worker_instance_id,count(*) as tasks from research_agent_task where research_run_id='$run_id' group by status,lease_epoch,fencing_token,attempt_count,worker_instance_id;
" noteweave

echo "MA4F_DUAL_WORKER_VERIFIED"
