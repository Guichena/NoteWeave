#!/bin/sh
# 新版本链路：等上一版评测的两路跑完、开始评分（评分不读 dev2，也不用这两个容器），同步 dev2 到 bundle 里的新提交，
# 构建 Worker 与后端镜像，先关闭补查部署（之后删掉上一版镜像，评测在磁盘低于 1GB 时会提前停止），
# 评测开发集与留出集并各评分两次，与上一版对照；再在同一镜像上打开补查评测一轮（confirm_chain.sh）
# 用法：version_chain.sh <新标签> <上一版标签>，例如 version_chain.sh fix19 fix18
TAG="${1:?tag}"; PREV="${2:?previous tag}"
EVAL=/data/workspace/noteweave-research-eval
DEPLOY=/data/workspace/noteweave-research-deploy
DEV_SRC=/data/workspace/noteweave-research-dev2
HOLDOUT=$DEV_SRC/experiments/deep-research/quality-eval/dataset-holdout.json
STATUS=$EVAL/final-chain-$TAG.status
log() { echo "$(date -u +%H:%M:%S) $*" >> "$STATUS"; }
log "wait for $PREV streams"
until grep -qE "score final-dev-$PREV|FAILED|IMAGE_MISMATCH" "$EVAL/final-chain-$PREV.status" 2>/dev/null; do sleep 60; done
grep -q "score final-dev-$PREV" "$EVAL/final-chain-$PREV.status" || { log "PREVIOUS_FAILED"; exit 1; }
cd "$DEV_SRC" && git merge -q --ff-only refs/remotes/bundle/research || { log "SYNC_FAILED"; exit 1; }
log "dev2 $(git log --oneline -1)"
"$DEPLOY/build_images.sh" "$TAG" > "$DEPLOY/build-$TAG.log" 2>&1
grep -q BUILD_DONE "$DEPLOY/build-$TAG.log" || { log "BUILD_FAILED"; exit 1; }
sed "s/$PREV/$TAG/g" "$DEPLOY/compose.$PREV.json" > "$DEPLOY/compose.$TAG.json"
sed "s/$PREV/$TAG/g" "$DEPLOY/compose.$PREV-nosupp.json" > "$DEPLOY/compose.$TAG-nosupp.json"
log "built $(head -1 "$DEPLOY/build-$TAG.log"); $(df -h / | tail -1)"
log "deploy compose.$TAG-nosupp.json"
"$DEPLOY/deploy.sh" "compose.$TAG-nosupp.json" >> "$STATUS" 2>&1 || { log "DEPLOY_FAILED"; exit 1; }
docker rmi "nw-research-worker:$PREV" "nw-research-backend:$PREV" >> "$STATUS" 2>&1
log "removed $PREV images; $(df -h / | tail -1)"
for pair in "final-dev-$TAG experiments/deep-research/quality-eval/dataset.json" "final-holdout-$TAG $HOLDOUT"; do
  set -- $pair
  "$EVAL/start_eval.sh" "$1" "$DEV_SRC" "$2" >> "$STATUS" 2>&1
  sleep 30
  "$EVAL/wait_eval.sh" "$1" >> "$STATUS" 2>&1 || { log "WAIT_FAILED $1"; exit 1; }
done
for label in final-dev-$TAG final-holdout-$TAG; do
  log "score $label"
  "$EVAL/score_eval.sh" "$label" > "$EVAL/runs/$label/score.log" 2>&1
  "$EVAL/score_eval.sh" "$label" > "$EVAL/runs/$label/score2.log" 2>&1
done
cd "$EVAL" && {
  python3 compare_eval.py final-dev-$PREV final-dev-$TAG
  echo
  python3 compare_eval.py final-holdout-$PREV final-holdout-$TAG
} > "$EVAL/final-compare-$TAG.md" 2>&1
log "NOSUPP_DONE"
OVERRIDE="compose.$TAG.json" "$EVAL/confirm_chain.sh" "$TAG" supp
log "CHAIN_DONE"
