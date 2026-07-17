#!/bin/sh
set -eu

bootstrap="${MA4G_KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
group_id="${MA4G_KAFKA_GROUP_ID:-noteweave-ma4g-workers}"
topic="${MA4G_KAFKA_TOPIC:-noteweave.research.agent.command}"
kafka_bin="${KAFKA_HOME:-/opt/bitnami/kafka}/bin"
timeout_seconds="${MA4G_REPLAY_TIMEOUT_SECONDS:-90}"

attempt=0
inactive=false
state="unknown"
members="unknown"
while [ "$attempt" -lt "$timeout_seconds" ]; do
  attempt=$((attempt + 1))
  state_output="$("$kafka_bin/kafka-consumer-groups.sh" --bootstrap-server "$bootstrap" \
      --group "$group_id" --describe --state 2>&1 || true)"
  state="$(printf '%s\n' "$state_output" | awk -v group="$group_id" '$1 == group { print $(NF - 1); exit }')"
  members="$(printf '%s\n' "$state_output" | awk -v group="$group_id" '$1 == group { print $NF; exit }')"
  case "$state" in Empty|Dead) inactive=true; break ;; esac
  if [ -z "$state" ] && printf '%s\n' "$state_output" | grep -Eqi 'has no active members|does not exist'; then
    inactive=true
    break
  fi
  sleep 1
done
printf '%s\n' "$state_output"
if [ "$inactive" != "true" ]; then
  echo "MA4G_REPLAY_OFFSET_RESET_FAILED group_state=$state members=$members" >&2
  exit 1
fi

partition_count="$("$kafka_bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --describe --topic "$topic" \
  | awk -F 'PartitionCount: ' '/PartitionCount:/ { split($2, fields, " "); print fields[1]; exit }')"
dry_run="$("$kafka_bin/kafka-consumer-groups.sh" --bootstrap-server "$bootstrap" --group "$group_id" \
  --topic "$topic" --reset-offsets --to-earliest --dry-run 2>&1)" || { printf '%s\n' "$dry_run" >&2; exit 1; }
printf '%s\n' "$dry_run"
dry_count="$(printf '%s\n' "$dry_run" | awk -v group="$group_id" -v topic="$topic" '$1 == group && $2 == topic && $3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ { count++ } END { print count + 0 }')"
if [ -z "$partition_count" ] || [ "$dry_count" != "$partition_count" ]; then
  echo "MA4G_REPLAY_DRY_RUN_FAILED expected=$partition_count actual=$dry_count" >&2
  exit 1
fi

reset="$("$kafka_bin/kafka-consumer-groups.sh" --bootstrap-server "$bootstrap" --group "$group_id" \
  --topic "$topic" --reset-offsets --to-earliest --execute 2>&1)" || { printf '%s\n' "$reset" >&2; exit 1; }
printf '%s\n' "$reset"
reset_count="$(printf '%s\n' "$reset" | awk -v group="$group_id" -v topic="$topic" '$1 == group && $2 == topic && $3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ { count++ } END { print count + 0 }')"
reset_total="$(printf '%s\n' "$reset" | awk -v group="$group_id" -v topic="$topic" '$1 == group && $2 == topic && $4 ~ /^[0-9]+$/ { total += $4 } END { print total + 0 }')"
end_total="$("$kafka_bin/kafka-get-offsets.sh" --bootstrap-server "$bootstrap" --topic "$topic" | awk -F ':' -v topic="$topic" '$1 == topic && $3 ~ /^[0-9]+$/ { total += $3 } END { print total + 0 }')"
records=$((end_total - reset_total))
if [ "$reset_count" != "$partition_count" ] || [ "$records" -le 0 ]; then
  echo "MA4G_REPLAY_OFFSET_RESET_FAILED expected=$partition_count actual=$reset_count replay_records=$records" >&2
  exit 1
fi
echo "MA4G_REPLAY_OFFSETS_RESET partitions=$reset_count replay_records=$records"

