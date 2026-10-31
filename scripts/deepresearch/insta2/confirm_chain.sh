#!/bin/sh
# 确认轮：不构建，在同一镜像上把开发集与留出集再评测一轮，评分后与同一镜像的上一轮对照
# 用法：[WAIT_FOR=状态文件] [OVERRIDE=覆盖文件] confirm_chain.sh <镜像标签> <轮次后缀>，例如 confirm_chain.sh fix17 r2
# WAIT_FOR 先等另一条链路结束；OVERRIDE 先用该覆盖文件重新部署（例如同一镜像只改配置开关），不给则沿用当前部署
# 评测目录下存在 skip-<镜像标签><轮次后缀> 文件时直接跳过，用于已决定不再需要的确认轮（例如补查开关已定）
TAG="${1:?tag}"; RUN="${2:?suffix}"
EVAL=/data/workspace/noteweave-research-eval
DEPLOY=/data/workspace/noteweave-research-deploy
DEV_SRC=/data/workspace/noteweave-research-dev2
HOLDOUT=$DEV_SRC/experiments/deep-research/quality-eval/dataset-holdout.json
STATUS=$EVAL/final-chain-$TAG$RUN.status
log() { echo "$(date -u +%H:%M:%S) $*" >> "$STATUS"; }
if [ -f "$EVAL/skip-$TAG$RUN" ]; then log "SKIPPED: $(cat "$EVAL/skip-$TAG$RUN")"; exit 0; fi
if [ -n "$WAIT_FOR" ]; then
  log "waiting for $WAIT_FOR"
  until grep -qE "CHAIN_DONE|FAILED|IMAGE_MISMATCH" "$EVAL/$WAIT_FOR" 2>/dev/null; do sleep 60; done
  grep -q CHAIN_DONE "$EVAL/$WAIT_FOR" || { log "PREVIOUS_FAILED"; exit 1; }
fi
if [ -n "$OVERRIDE" ]; then
  log "deploy $OVERRIDE"
  "$DEPLOY/deploy.sh" "$OVERRIDE" >> "$STATUS" 2>&1 || { log "DEPLOY_FAILED"; exit 1; }
fi
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
