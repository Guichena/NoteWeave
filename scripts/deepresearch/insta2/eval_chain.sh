#!/bin/sh
# 最终版本在开发集与留出集上各评测一轮：等待镜像构建完成，部署，依次评测，评分并与基线对照
TAG="${1:?tag}"
EVAL=/data/workspace/noteweave-research-eval
DEPLOY=/data/workspace/noteweave-research-deploy
DEV_SRC=/data/workspace/noteweave-research-dev2
HOLDOUT=$DEV_SRC/experiments/deep-research/quality-eval/dataset-holdout.json
STATUS=$EVAL/final-chain-$TAG.status
log() { echo "$(date -u +%H:%M:%S) $*" >> "$STATUS"; }
log "wait build"
while ! grep -q BUILD_DONE "$DEPLOY/build-$TAG.log" 2>/dev/null; do
  pgrep -f "[b]uild_images.sh $TAG" >/dev/null || { log "BUILD_FAILED"; exit 1; }
  sleep 30
done
log "deploy $TAG"
"$DEPLOY/deploy.sh" "compose.$TAG.json" >> "$STATUS" 2>&1 || { log "DEPLOY_FAILED"; exit 1; }
docker rmi "nw-research-worker:${PREV:-none}" "nw-research-backend:${PREV:-none}" >/dev/null 2>&1
for pair in "final-dev-$TAG experiments/deep-research/quality-eval/dataset.json" "final-holdout-$TAG $HOLDOUT"; do
  set -- $pair
  "$EVAL/start_eval.sh" "$1" "$DEV_SRC" "$2" >> "$STATUS" 2>&1
  sleep 30
  "$EVAL/wait_eval.sh" "$1" >> "$STATUS" 2>&1 || { log "WAIT_FAILED $1"; exit 1; }
done
for label in final-dev-$TAG final-holdout-$TAG; do
  log "score $label"
  "$EVAL/score_eval.sh" "$label" > "$EVAL/runs/$label/score.log" 2>&1
  # 评审偶尔输出无效导致漏判，再评一次补齐（已有结果走缓存）
  "$EVAL/score_eval.sh" "$label" > "$EVAL/runs/$label/score2.log" 2>&1
done
cd "$EVAL" && {
  python3 compare_eval.py baseline-legacy-083b-r1 e1-fix8-loop1 final-dev-fix9 final-dev-$TAG
  echo
  python3 compare_eval.py holdout-baseline-083b holdout-fix8-loop1 final-holdout-fix9 final-holdout-$TAG
} > "$EVAL/final-compare-$TAG.md" 2>&1
log "CHAIN_DONE"
