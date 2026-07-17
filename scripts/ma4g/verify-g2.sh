#!/bin/sh
set -eu

phase="${1:-}"
case "$phase" in prekill|postkill|recovered) ;; *) echo "usage: verify-g2.sh prekill|postkill|recovered" >&2; exit 2 ;; esac
export MYSQL_PWD="${MYSQL_PWD:-noteweave123}"
mysql_value() { mysql -hmysql -unoteweave --batch --raw --skip-column-names noteweave -e "$1"; }
run_id="00000000-0000-0000-0000-00000000a002"
assert_eq() {
  label="$1"; expected="$2"; actual="$3"
  if [ "$actual" != "$expected" ]; then
    echo "MA4G_G2_ASSERTION_FAILED phase=$phase $label expected=$expected actual=$actual" >&2
    exit 1
  fi
  echo "MA4G_G2_ASSERTION_OK phase=$phase $label=$actual"
}

if [ "$phase" = "recovered" ]; then
  sh /fixture/verify-atomic.sh 1 2 1
  assert_eq lease_epoch 2 "$(mysql_value "select lease_epoch from research_agent_task where research_run_id='$run_id'")"
  assert_eq fencing_token 2 "$(mysql_value "select fencing_token from research_agent_task where research_run_id='$run_id'")"
  assert_eq attempt_count 2 "$(mysql_value "select attempt_count from research_agent_task where research_run_id='$run_id'")"
  assert_eq final_worker ma4g-worker-b "$(mysql_value "select worker_instance_id from research_agent_task where research_run_id='$run_id'")"
  assert_eq outbox_delivery 1 "$(mysql_value "select delivery_no from research_agent_outbox where research_run_id='$run_id'")"
  assert_eq trigger_removed 0 "$(mysql_value "select count(*) from information_schema.triggers where trigger_schema=database() and trigger_name='ma4g_sleep_before_second_cell_cas'")"
  echo "MA4G_G2_RECOVERY_VERIFIED"
  exit 0
fi

assert_eq task_count 1 "$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id'")"
assert_eq task_status CLAIMED "$(mysql_value "select status from research_agent_task where research_run_id='$run_id'")"
assert_eq lease_epoch 1 "$(mysql_value "select lease_epoch from research_agent_task where research_run_id='$run_id'")"
assert_eq fencing_token 1 "$(mysql_value "select fencing_token from research_agent_task where research_run_id='$run_id'")"
assert_eq attempt_count 1 "$(mysql_value "select attempt_count from research_agent_task where research_run_id='$run_id'")"
assert_eq fault_worker ma4g-g2-fault-worker "$(mysql_value "select worker_instance_id from research_agent_task where research_run_id='$run_id'")"
assert_eq active_bound_cells 2 "$(mysql_value "select count(*) from research_cell c join research_agent_task t on t.id=c.active_task_id where c.research_run_id='$run_id' and t.worker_instance_id='ma4g-g2-fault-worker' and c.cell_version=0")"
assert_eq completion_count 0 "$(mysql_value "select count(*) from research_agent_completion c join research_agent_task t on t.id=c.research_agent_task_id where t.research_run_id='$run_id'")"
assert_eq execution_count 0 "$(mysql_value "select count(*) from research_agent_execution e join research_agent_task t on t.id=e.research_agent_task_id where t.research_run_id='$run_id'")"
assert_eq evidence_count 0 "$(mysql_value "select count(*) from source_evidence where research_run_id='$run_id'")"
assert_eq candidate_count 0 "$(mysql_value "select count(*) from research_agent_candidate where research_run_id='$run_id'")"
assert_eq merge_count 0 "$(mysql_value "select count(*) from research_cell_merge where research_run_id='$run_id'")"
assert_eq lineage_count 0 "$(mysql_value "select count(*) from research_cell_evidence where research_run_id='$run_id'")"
assert_eq reserved_budget 1 "$(mysql_value "select count(*) from research_budget_reservation where research_run_id='$run_id' and state='RESERVED' and agent_completion_id is null")"
assert_eq trigger_present 1 "$(mysql_value "select count(*) from information_schema.triggers where trigger_schema=database() and trigger_name='ma4g_sleep_before_second_cell_cas'")"
echo "MA4G_G2_${phase}_VERIFIED"
