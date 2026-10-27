#!/bin/sh
# 模型接口忽略系统消息时评分整轮作废（2026-10-27 18:26 起）。每 10 分钟探测一次，恢复后先给等待重评的轮次评分两次并与对照轮比较，
# 再跳过等待上一版、启动新版本链路（与对照轮比较）
# 用法：outage_resume.sh <新标签> <上一版标签> [重评轮次后缀 对照轮次后缀]，例如 outage_resume.sh fix21 fix20 fix19supp fix19
EVAL=/data/workspace/noteweave-research-eval
TAG="${1:?tag}"; PREV="${2:?previous tag}"; RESCORE="${3:-}"; BASELINE="${4:-}"
STATUS=$EVAL/final-chain-$TAG.status
log() { echo "$(date -u +%H:%M:%S) $*" >> "$STATUS"; }
cd "$EVAL" || exit 1
log "waiting for the model API to honor system messages"
until python3 run_with_env.py exp/llm_ready.py 5 >> "$STATUS" 2>&1; do sleep 600; done
log "model API ready"
if [ -n "$RESCORE" ]; then
  for set in dev holdout; do
    for round in 1 2; do "$EVAL/score_eval.sh" "final-$set-$RESCORE" > "$EVAL/runs/final-$set-$RESCORE/rescore$round.log" 2>&1; done
    log "rescored final-$set-$RESCORE"
  done
  {
    python3 "$EVAL/compare_eval.py" "final-dev-$BASELINE" "final-dev-$RESCORE"
    echo
    python3 "$EVAL/compare_eval.py" "final-holdout-$BASELINE" "final-holdout-$RESCORE"
  } > "$EVAL/final-compare-$RESCORE.md" 2>&1
  log "RESCORE_DONE final-compare-$RESCORE.md"
fi
# 上一版中途作废，没有评分，新版本与对照轮比较
SKIP_WAIT=1 COMPARE_WITH="${BASELINE:-$PREV}" exec "$EVAL/version_chain.sh" "$TAG" "$PREV"
