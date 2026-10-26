#!/bin/sh
# 确认轮：不构建、不部署，在当前已部署的镜像上把开发集与留出集再评测一轮，评分后与同一镜像的上一轮对照
# 用法：confirm_chain.sh <镜像标签> <轮次后缀>，例如 confirm_chain.sh fix17 r2
TAG="${1:?tag}"; RUN="${2:?suffix}"
EVAL=/data/workspace/noteweave-research-eval
DEV_SRC=/data/workspace/noteweave-research-dev2
HOLDOUT=$DEV_SRC/experiments/deep-research/quality-eval/dataset-holdout.json
STATUS=$EVAL/final-chain-$TAG$RUN.status
log() { echo "$(date -u +%H:%M:%S) $*" >> "$STATUS"; }
running=$(docker inspect -f "{{.Config.Image}}" noteweave-v2-research-worker-consumer 2>/dev/null)
[ "$running" = "nw-research-worker:$TAG" ] || { log "IMAGE_MISMATCH $running"; exit 1; }
log "confirm $TAG run $RUN"
for pair in "final-dev-$TAG$RUN experiments/deep-research/quality-eval/dataset.json" "final-holdout-$TAG$RUN $HOLDOUT"; do
  set -- $pair
  "$EVAL/start_eval.sh" "$1" "$DEV_SRC" "$2" >> "$STATUS" 2>&1
  sleep 30
  "$EVAL/wait_eval.sh" "$1" >> "$STATUS" 2>&1 || { log "WAIT_FAILED $1"; exit 1; }
done
for label in final-dev-$TAG$RUN final-holdout-$TAG$RUN; do
  log "score $label"
  "$EVAL/score_eval.sh" "$label" > "$EVAL/runs/$label/score.log" 2>&1
  "$EVAL/score_eval.sh" "$label" > "$EVAL/runs/$label/score2.log" 2>&1
done
cd "$EVAL" && {
  python3 compare_eval.py final-dev-$TAG final-dev-$TAG$RUN
  echo
  python3 compare_eval.py final-holdout-$TAG final-holdout-$TAG$RUN
} > "$EVAL/final-compare-$TAG$RUN.md" 2>&1
log "CHAIN_DONE"
