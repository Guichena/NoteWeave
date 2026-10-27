#!/bin/sh
# 只重跑开发集：等上一条链路结束后，在当前已部署的镜像上评测开发集，评分两次后与同一镜像的首轮对照
# 用法：rerun_dev.sh <镜像标签> <轮次后缀> <等待的状态文件>，例如 rerun_dev.sh fix17 nosupp2 final-chain-fix17nosupp.status
TAG="${1:?tag}"; RUN="${2:?suffix}"; WAIT_FOR="${3:-}"
EVAL=/data/workspace/noteweave-research-eval
DEV_SRC=/data/workspace/noteweave-research-dev2
STATUS=$EVAL/final-chain-$TAG$RUN.status
LABEL=final-dev-$TAG$RUN
log() { echo "$(date -u +%H:%M:%S) $*" >> "$STATUS"; }
if [ -n "$WAIT_FOR" ]; then
  log "waiting for $WAIT_FOR"
  until grep -qE "CHAIN_DONE|FAILED|IMAGE_MISMATCH" "$EVAL/$WAIT_FOR" 2>/dev/null; do sleep 60; done
fi
running=$(docker inspect -f "{{.Config.Image}}" noteweave-v2-research-worker-consumer 2>/dev/null)
[ "$running" = "nw-research-worker:$TAG" ] || { log "IMAGE_MISMATCH $running"; exit 1; }
log "rerun dev $TAG run $RUN"
"$EVAL/start_eval.sh" "$LABEL" "$DEV_SRC" experiments/deep-research/quality-eval/dataset.json >> "$STATUS" 2>&1
sleep 30
"$EVAL/wait_eval.sh" "$LABEL" >> "$STATUS" 2>&1 || { log "WAIT_FAILED $LABEL"; exit 1; }
log "score $LABEL"
"$EVAL/score_eval.sh" "$LABEL" > "$EVAL/runs/$LABEL/score.log" 2>&1
"$EVAL/score_eval.sh" "$LABEL" > "$EVAL/runs/$LABEL/score2.log" 2>&1
cd "$EVAL" && python3 compare_eval.py final-dev-$TAG $LABEL > "$EVAL/final-compare-$TAG$RUN.md" 2>&1
log "CHAIN_DONE"
