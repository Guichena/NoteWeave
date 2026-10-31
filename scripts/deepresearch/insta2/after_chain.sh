#!/bin/sh
# 等另一条确认链路的两路评测跑完（状态文件出现 score final-dev-<轮次>，评分阶段不读 dev2、不用容器），再启动新版本链路
# 用法：after_chain.sh <等待的状态文件名> <等待的轮次标签> <新标签> <上一版标签>
# 例如 after_chain.sh final-chain-fix19supp.status fix19supp fix20 fix19
EVAL=/data/workspace/noteweave-research-eval
WAIT_STATUS="$EVAL/${1:?status file}"; WAIT_LABEL="${2:?label}"; TAG="${3:?tag}"; PREV="${4:?previous tag}"
STATUS=$EVAL/final-chain-$TAG.status
echo "$(date -u +%H:%M:%S) waiting for $WAIT_LABEL streams" >> "$STATUS"
until grep -qE "score final-dev-$WAIT_LABEL|FAILED|IMAGE_MISMATCH" "$WAIT_STATUS" 2>/dev/null; do sleep 60; done
grep -q "score final-dev-$WAIT_LABEL" "$WAIT_STATUS" || { echo "$(date -u +%H:%M:%S) WAITED_CHAIN_FAILED" >> "$STATUS"; exit 1; }
exec "$EVAL/version_chain.sh" "$TAG" "$PREV"
