#!/bin/sh
set -eu
bootstrap="${MA4G_KAFKA_BOOTSTRAP_SERVERS:-kafka:9092}"
topic="${MA4G_KAFKA_TOPIC:-noteweave.research.agent.command}"
kafka_bin="${KAFKA_HOME:-/opt/bitnami/kafka}/bin"
offsets="$("$kafka_bin/kafka-get-offsets.sh" --bootstrap-server "$bootstrap" --topic "$topic")"
printf '%s\n' "$offsets"
used="$(printf '%s\n' "$offsets" | awk -F ':' -v topic="$topic" '$1 == topic && $3 ~ /^[0-9]+$/ && $3 > 0 { count++ } END { print count + 0 }')"
records="$(printf '%s\n' "$offsets" | awk -F ':' -v topic="$topic" '$1 == topic && $3 ~ /^[0-9]+$/ { total += $3 } END { print total + 0 }')"
if [ "$used" -lt 2 ] || [ "$records" -lt 2 ]; then
  echo "MA4G_MULTI_PARTITION_ASSERTION_FAILED used=$used records=$records" >&2
  exit 1
fi
echo "MA4G_MULTI_PARTITION_VERIFIED used=$used records=$records"

