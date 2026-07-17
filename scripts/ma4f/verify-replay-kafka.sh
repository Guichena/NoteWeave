#!/bin/sh
set -eu

bootstrap="${MA4F_KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
group_id="${MA4F_REPLAY_GROUP_ID:-noteweave-ma4f-workers}"
topic="${MA4F_REPLAY_TOPIC:-noteweave.research.agent.command}"
dlq_topic="${MA4F_REPLAY_DLQ_TOPIC:-noteweave.research.agent.command.dlq}"
timeout_seconds="${MA4F_REPLAY_TIMEOUT_SECONDS:-120}"
kafka_bin="${KAFKA_HOME:-/opt/bitnami/kafka}/bin"

partition_count="$("$kafka_bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --describe --topic "$topic" \
  | awk -F 'PartitionCount: ' 'NR == 1 { split($2, fields, " "); print fields[1] }')"

attempt=0
lag="unknown"
rows=0
unknown_nonempty=0
while [ "$attempt" -lt "$timeout_seconds" ]; do
  attempt=$((attempt + 1))
  describe_output="$("$kafka_bin/kafka-consumer-groups.sh" --bootstrap-server "$bootstrap" --describe --group "$group_id" 2>&1 || true)"
  metrics="$(printf '%s\n' "$describe_output" | awk -v group="$group_id" -v topic="$topic" '
    $1 == group && $2 == topic && $3 ~ /^[0-9]+$/ {
      rows++
      if ($6 ~ /^[0-9]+$/) {
        lag += $6
      } else if ($5 ~ /^[0-9]+$/ && $5 != 0) {
        unknown_nonempty++
      }
    }
    END { print rows + 0, lag + 0, unknown_nonempty + 0 }
  ')"
  rows="$(printf '%s' "$metrics" | awk '{print $1}')"
  lag="$(printf '%s' "$metrics" | awk '{print $2}')"
  unknown_nonempty="$(printf '%s' "$metrics" | awk '{print $3}')"
  if [ -n "$partition_count" ] && [ "$rows" = "$partition_count" ] \
      && [ "$lag" = "0" ] && [ "$unknown_nonempty" = "0" ]; then
    break
  fi
  sleep 1
done

if [ -z "$partition_count" ] || [ "$rows" != "$partition_count" ] \
    || [ "$lag" != "0" ] || [ "$unknown_nonempty" != "0" ]; then
  echo "MA4F_REPLAY_LAG_ASSERTION_FAILED partitions=$partition_count described=$rows lag=$lag unknown_nonempty=$unknown_nonempty" >&2
  exit 1
fi

dlq_offsets="$("$kafka_bin/kafka-get-offsets.sh" --bootstrap-server "$bootstrap" --topic "$dlq_topic")"
printf '%s\n' "$dlq_offsets"
dlq_partition_count="$(printf '%s\n' "$dlq_offsets" | awk -F ':' -v topic="$dlq_topic" '$1 == topic && $2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ { count++ } END { print count + 0 }')"
dlq_end_offset="$(printf '%s\n' "$dlq_offsets" | awk -F ':' -v topic="$dlq_topic" '$1 == topic && $3 ~ /^[0-9]+$/ { total += $3 } END { print total + 0 }')"
if [ "$dlq_partition_count" = "0" ] || [ "$dlq_end_offset" != "0" ]; then
  echo "MA4F_REPLAY_DLQ_ASSERTION_FAILED partitions=$dlq_partition_count end_offset=$dlq_end_offset" >&2
  exit 1
fi

echo "MA4F_REPLAY_KAFKA_VERIFIED lag=0 dlq_end_offset=0"
