#!/bin/sh
set -eu

root="${MA4G_LOCK_MATRIX_EVIDENCE_DIR:-/control/lock-matrix}"
summary="$root/summary.json"

test -s "$summary"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"VERIFIED"' "$summary"
grep -Eq '"test_only_harness_removed"[[:space:]]*:[[:space:]]*true' "$summary"
grep -Eq '"transaction_isolation"[[:space:]]*:[[:space:]]*"READ-COMMITTED"' "$summary"
grep -Eq '"performance_schema"[[:space:]]*:[[:space:]]*"1"' "$summary"

for case_id in a b c d e f g h i j k l1 l2; do
  case_file="$root/case-$case_id.json"
  test -s "$case_file"
  grep -Eq '"status"[[:space:]]*:[[:space:]]*"VERIFIED"' "$case_file"
  grep -Eq '"proved_chain"[[:space:]]*:[[:space:]]*"follower -> leader -> gate-holder"' "$case_file"
  grep -Eq '"holder_connection_id"[[:space:]]*:' "$case_file"
  grep -Eq '"leader_connection_id"[[:space:]]*:' "$case_file"
  grep -Eq '"follower_connection_id"[[:space:]]*:' "$case_file"
  grep -Eq '"lock_deadlocks"[[:space:]]*:' "$case_file"
  grep -Eq '"lock_timeouts"[[:space:]]*:' "$case_file"
  grep -Eq '"state_before"[[:space:]]*:' "$case_file"
  grep -Eq '"state_after"[[:space:]]*:' "$case_file"
  grep -Eq 'updated_at' "$case_file"
done

if find "$root" -maxdepth 1 -type f -name 'case-*.json' | grep -q .; then
  find "$root" -maxdepth 1 -type f -name 'case-*.json' -print | sort
else
  echo "LockMatrix case evidence is missing" >&2
  exit 1
fi

cat "$summary"
echo "MA4G_LOCK_MATRIX_VERIFIED cases=13"
