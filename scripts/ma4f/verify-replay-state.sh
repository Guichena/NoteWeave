#!/bin/sh
set -eu

mode="${1:-assert}"
run_id="00000000-0000-0000-0000-00000000f002"
export MYSQL_PWD="${MYSQL_PWD:-noteweave123}"

mysql_query() {
  mysql -hmysql -unoteweave --batch --raw --skip-column-names noteweave "$@"
}

mysql_value() {
  mysql_query -e "$1"
}

assert_eq() {
  label="$1"; expected="$2"; actual="$3"
  if [ "$actual" != "$expected" ]; then
    echo "MA4F_REPLAY_ASSERTION_FAILED $label expected=$expected actual=$actual" >&2
    exit 1
  fi
  echo "MA4F_REPLAY_ASSERTION_OK $label=$actual"
}

case "$mode" in
  capture|assert) ;;
  *) echo "usage: verify-replay-state.sh capture|assert" >&2; exit 2 ;;
esac

if [ "$mode" = "capture" ]; then
  for attempt in $(seq 1 120); do
    status="$(mysql_value "select coalesce(max(status), '') from research_agent_task where research_run_id='$run_id'")"
    [ "$status" = "SUBMITTED" ] && break
    sleep 1
  done

  # Do not snapshot a merely partial baseline.
  assert_eq baseline_task_count 1 "$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id'")"
  assert_eq baseline_submitted_task_count 1 "$(mysql_value "select count(*) from research_agent_task where research_run_id='$run_id' and status='SUBMITTED'")"
  assert_eq baseline_execution_count 1 "$(mysql_value "select count(*) from research_agent_execution e join research_agent_task t on t.id=e.research_agent_task_id where t.research_run_id='$run_id'")"
  assert_eq baseline_evidence_count 1 "$(mysql_value "select count(*) from source_evidence where research_run_id='$run_id'")"
  assert_eq baseline_candidate_count 1 "$(mysql_value "select count(*) from research_agent_candidate where research_run_id='$run_id'")"
  assert_eq baseline_accepted_merge_count 1 "$(mysql_value "select count(*) from research_cell_merge where research_run_id='$run_id' and decision='ACCEPTED'")"
  assert_eq baseline_verified_cell_count 1 "$(mysql_value "select count(*) from research_cell where research_run_id='$run_id' and cell_status='VERIFIED' and cell_version=1 and active_task_id is null")"
  assert_eq baseline_settled_budget_count 1 "$(mysql_value "select count(*) from research_budget_reservation where research_run_id='$run_id' and state='SETTLED'")"
  assert_eq baseline_sent_outbox_count 1 "$(mysql_value "select count(*) from research_agent_outbox where research_run_id='$run_id' and status='SENT'")"
  assert_eq baseline_delivery_failure_count 0 "$(mysql_value "select count(*) from research_agent_delivery_failure where research_run_id='$run_id'")"
fi

snapshot="$(mysql_query < /fixture/replay-state.sql)"
printf '%s\n' "$snapshot"

overall="$(printf '%s\n' "$snapshot" | awk -F '\t' '$1 == "OVERALL" { print $3 "\t" $4 }')"
overall_count="$(printf '%s\n' "$overall" | awk -F '\t' '{print $1}')"
overall_digest="$(printf '%s\n' "$overall" | awk -F '\t' '{print $2}')"
if ! printf '%s' "$overall_digest" | grep -Eq '^[0-9a-f]{64}$'; then
  echo "MA4F_REPLAY_ASSERTION_FAILED invalid_state_digest value=$overall_digest" >&2
  exit 1
fi

mysql_value "
create table if not exists ma4f_replay_fixture_baseline (
  research_run_id varchar(36) not null,
  component varchar(96) not null,
  row_count bigint not null,
  component_digest char(64) not null,
  primary key (research_run_id, component)
) engine=InnoDB;
" >/dev/null

if [ "$mode" = "capture" ]; then
  existing="$(mysql_value "select count(*) from ma4f_replay_fixture_baseline where research_run_id='$run_id'")"
  if [ "$existing" = "0" ]; then
    tab="$(printf '\t')"
    printf '%s\n' "$snapshot" | while IFS="$tab" read -r kind component row_count digest; do
      case "$kind" in COMPONENT|OVERALL) ;; *) continue ;; esac
      if ! printf '%s' "$component" | grep -Eq '^(__OVERALL__|[a-z_]+)$' \
          || ! printf '%s' "$row_count" | grep -Eq '^[0-9]+$' \
          || ! printf '%s' "$digest" | grep -Eq '^[0-9a-f]{64}$'; then
        echo "MA4F_REPLAY_ASSERTION_FAILED invalid_snapshot_line component=$component" >&2
        exit 1
      fi
      mysql_value "insert into ma4f_replay_fixture_baseline
        (research_run_id, component, row_count, component_digest)
        values ('$run_id', '$component', $row_count, '$digest')" >/dev/null
    done
  fi
fi

baseline_count="$(mysql_value "select row_count from ma4f_replay_fixture_baseline where research_run_id='$run_id' and component='__OVERALL__'")"
baseline_digest="$(mysql_value "select component_digest from ma4f_replay_fixture_baseline where research_run_id='$run_id' and component='__OVERALL__'")"
if [ -z "$baseline_digest" ]; then
  echo "MA4F_REPLAY_ASSERTION_FAILED replay baseline has not been captured" >&2
  exit 1
fi

if [ "$overall_count" != "$baseline_count" ] || [ "$overall_digest" != "$baseline_digest" ]; then
  echo "MA4F_REPLAY_STATE_CHANGED baseline_rows=$baseline_count current_rows=$overall_count baseline_digest=$baseline_digest current_digest=$overall_digest" >&2
  exit 1
fi

if [ "$mode" = "capture" ]; then
  echo "MA4F_REPLAY_BASELINE_CAPTURED rows=$overall_count digest=$overall_digest"
else
  echo "MA4F_REPLAY_STATE_UNCHANGED rows=$overall_count digest=$overall_digest"
fi
