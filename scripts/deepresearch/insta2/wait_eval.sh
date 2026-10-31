#!/bin/sh
# 用法：wait_eval.sh <标签>；等待评测进程结束，期间磁盘低于 1GB 或 Worker 容器退出时提前返回
LABEL="$1"
DIR=/data/workspace/noteweave-research-eval/runs/$LABEL
while pgrep -f "[r]un_quality_eval.py.*runs/$LABEL/" >/dev/null; do
  free_kb=$(df --output=avail / | tail -1)
  if [ "$free_kb" -lt 1048576 ]; then echo "LOW_DISK $free_kb"; exit 3; fi
  state=$(docker inspect -f '{{.State.Status}}' noteweave-v2-research-worker-consumer 2>/dev/null || echo missing)
  if [ "$state" != running ]; then echo "WORKER_$state"; docker logs --tail 20 noteweave-v2-research-worker-consumer 2>&1 | grep -v "HTTP Request" | tail -8; exit 4; fi
  sleep 60
done
echo "DONE $(ls "$DIR"/stream*/ 2>/dev/null | grep -c json)"
tail -n 2 "$DIR"/stream*.log | cut -c1-200
df -h / | tail -1
