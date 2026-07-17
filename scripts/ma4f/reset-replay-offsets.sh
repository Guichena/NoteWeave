#!/bin/sh
set -eu

bootstrap="${MA4F_KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
group_id="${MA4F_REPLAY_GROUP_ID:-noteweave-ma4f-workers}"
topic="${MA4F_REPLAY_TOPIC:-noteweave.research.agent.command}"
kafka_bin="${KAFKA_HOME:-/opt/bitnami/kafka}/bin"
timeout_seconds="${MA4F_REPLAY_TIMEOUT_SECONDS:-60}"

# kafka-consumer-groups may print a reset error while still returning zero, so
# first require an explicitly inactive group instead of trusting exit status.
attempt=0
group_inactive=false
while [ "$attempt" -lt "$timeout_seconds" ]; do
  attempt=$((attempt + 1))
  state_output="$("$kafka_bin/kafka-consumer-groups.sh" \
      --bootstrap-server "$bootstrap" \
      --group "$group_id" \
      --describe --state 2>&1 || true)"
  state="$(printf '%s\n' "$state_output" | awk -v group="$group_id" '$1 == group { print $(NF - 1); exit }')"
  members="$(printf '%s\n' "$state_output" | awk -v group="$group_id" '$1 == group { print $NF; exit }')"
  case "$state" in
    Empty|Dead)
      group_inactive=true
      break
      ;;
  esac
  if [ -z "$state" ] && printf '%s\n' "$state_output" \
      | grep -Eqi 'has no active members|does not exist'; then
    group_inactive=true
    break
  fi
  sleep 1
done
printf '%s\n' "$state_output"
if [ "$group_inactive" != "true" ]; then
  echo "MA4F_REPLAY_OFFSET_RESET_FAILED group_state=$state members=$members" >&2
  exit 1
fi

partition_count="$("$kafka_bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --describe --topic "$topic" \
  | awk -F 'PartitionCount: ' '/PartitionCount:/ { split($2, fields, " "); print fields[1]; exit }')"

# The dry-run output must contain one numeric proposal per partition.  This
# catches Kafka versions that report reset rejection as text with exit code 0.
dry_run_output="$("$kafka_bin/kafka-consumer-groups.sh" \
  --bootstrap-server "$bootstrap" \
  --group "$group_id" \
  --topic "$topic" \
  --reset-offsets --to-earliest --dry-run 2>&1)" || {
    printf '%s\n' "$dry_run_output" >&2
    exit 1
  }
printf '%s\n' "$dry_run_output"
dry_run_count="$(printf '%s\n' "$dry_run_output" | awk -v group="$group_id" -v topic="$topic" \
  '$1 == group && $2 == topic && $3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ { count++ } END { print count + 0 }')"
if [ -z "$partition_count" ] || [ "$dry_run_count" != "$partition_count" ]; then
  echo "MA4F_REPLAY_OFFSET_DRY_RUN_FAILED expected_partitions=$partition_count proposed_partitions=$dry_run_count" >&2
  exit 1
fi

reset_output="$("$kafka_bin/kafka-consumer-groups.sh" \
  --bootstrap-server "$bootstrap" \
  --group "$group_id" \
  --topic "$topic" \
  --reset-offsets --to-earliest --execute 2>&1)" || {
    printf '%s\n' "$reset_output" >&2
    exit 1
  }
printf '%s\n' "$reset_output"

reset_count="$(printf '%s\n' "$reset_output" | awk -v group="$group_id" -v topic="$topic" \
  '$1 == group && $2 == topic && $3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ { count++ } END { print count + 0 }')"

if [ -z "$partition_count" ] || [ "$reset_count" != "$partition_count" ]; then
  echo "MA4F_REPLAY_OFFSET_RESET_FAILED expected_partitions=$partition_count reset_partitions=$reset_count" >&2
  exit 1
fi

reset_offset_total="$(printf '%s\n' "$reset_output" | awk -v group="$group_id" -v topic="$topic" \
  '$1 == group && $2 == topic && $4 ~ /^[0-9]+$/ { total += $4 } END { print total + 0 }')"
end_offset_total="$("$kafka_bin/kafka-get-offsets.sh" --bootstrap-server "$bootstrap" --topic "$topic" \
  | awk -F ':' -v topic="$topic" '$1 == topic && $3 ~ /^[0-9]+$/ { total += $3 } END { print total + 0 }')"
replay_records=$((end_offset_total - reset_offset_total))
if [ "$replay_records" -le 0 ]; then
  echo "MA4F_REPLAY_OFFSET_RESET_FAILED reset produced no replayable records reset_total=$reset_offset_total end_total=$end_offset_total" >&2
  exit 1
fi

echo "MA4F_REPLAY_OFFSETS_RESET group=$group_id topic=$topic partitions=$reset_count replay_records=$replay_records"
