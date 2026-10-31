#!/bin/sh
# 用法：score_eval.sh <标签>；合并两路结果后评分（聊天模型，三次取多数），报告复制到该轮目录
set -eu
LABEL="$1"
ROOT=/data/workspace/noteweave-research-eval
OUT="$ROOT/runs/$LABEL"
DEV=/data/workspace/noteweave-research-dev
mkdir -p "$OUT/merged"
cp "$OUT"/stream*/*.json "$OUT/merged/"
cd "$DEV"
DATASET=$(cat "$OUT/dataset.txt" 2>/dev/null || echo experiments/deep-research/quality-eval/dataset.json)
before=$(ls experiments/deep-research/quality-eval/reports 2>/dev/null | sort)
python3 "$ROOT/run_with_env.py" scripts/deepresearch/score_quality_eval.py --runs-dir "$OUT/merged" \
  --dataset "$DATASET" --judge-from-chat --judge-samples 3 --judge-cache "$ROOT/judge-cache.json"
after=$(ls experiments/deep-research/quality-eval/reports | sort)
for f in $(printf '%s\n%s\n' "$before" "$after" | sort | uniq -u); do
  mv "experiments/deep-research/quality-eval/reports/$f" "$OUT/"
done
ls "$OUT"
