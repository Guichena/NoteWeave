#!/bin/sh
set -eu

bootstrap="${MA4G_KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
group_id="${MA4G_KAFKA_GROUP_ID:-noteweave-ma4g-workers}"
kafka_bin="${KAFKA_HOME:-/opt/bitnami/kafka}/bin"
timeout_seconds="${MA4G_MEMBER_TIMEOUT_SECONDS:-30}"

attempt=0
while [ "$attempt" -lt "$timeout_seconds" ]; do
  attempt=$((attempt + 1))
  output="$("$kafka_bin/kafka-consumer-groups.sh" --bootstrap-server "$bootstrap" \
      --describe --group "$group_id" --members --verbose 2>&1 || true)"
  metrics="$(printf '%s\n' "$output" | awk -v group="$group_id" '
    $1 == group && $2 !~ /^CONSUMER/ {
      members++
      if ($5 ~ /^[0-9]+$/) partitions += $5
    }
    END { print members + 0, partitions + 0 }
  ')"
  members="$(printf '%s' "$metrics" | awk '{print $1}')"
  partitions="$(printf '%s' "$metrics" | awk '{print $2}')"
  if [ "$members" -ge 2 ] && [ "$partitions" -ge 2 ]; then
    printf '%s\n' "$output"
    echo "MA4G_KAFKA_MEMBERS_CAPTURED members=$members assigned_partitions=$partitions"
    exit 0
  fi
  sleep 1
done
printf '%s\n' "$output"
echo "MA4G_KAFKA_MEMBER_ASSERTION_FAILED members=$members assigned_partitions=$partitions" >&2
exit 1

