#!/bin/sh
# 用法：[EXCLUDE="题目1 题目2"] start_eval.sh <标签> [源码目录] [数据集相对路径]；题目按奇偶分两路并行，每路一个独立结果目录。
# EXCLUDE 用于中断后续跑：跳过已经有有效结果的题目。
# 源码目录必须与当前部署的镜像对应，评测清单会记录它的 HEAD 与文件摘要。
set -eu
LABEL="$1"
SRC="${2:-/data/workspace/noteweave-research}"
DATASET="${3:-experiments/deep-research/quality-eval/dataset.json}"
ROOT=/data/workspace/noteweave-research-eval
OUT="$ROOT/runs/$LABEL"
mkdir -p "$OUT"
cd "$SRC"
DATASET="$DATASET" EXCLUDE="${EXCLUDE:-}" python3 - > "$OUT/streams.txt" <<'EOF'
import json, os
excluded = set(os.environ.get("EXCLUDE", "").split())
cases = [c["case_key"] for c in json.load(open(os.environ["DATASET"], encoding="utf-8"))["cases"] if c["case_key"] not in excluded]
print(" ".join(cases[0::2]))
print(" ".join(cases[1::2]))
EOF
echo "$DATASET" > "$OUT/dataset.txt"
git rev-parse HEAD > "$OUT/git-head.txt"
git status --short > "$OUT/git-status.txt"
echo "$SRC" > "$OUT/source-dir.txt"
docker inspect noteweave-v2-backend noteweave-v2-research-worker-consumer --format '{{.Name}} {{.Config.Image}} {{.Image}}' > "$OUT/images.txt"
# 续跑时结果目录接在已有的 stream 目录之后，评测脚本不允许复用已有运行清单的目录
n=$(ls -d "$OUT"/stream*/ 2>/dev/null | wc -l)
while read -r line; do
  n=$((n + 1))
  args=""
  for key in $line; do args="$args --case $key"; done
  setsid nohup python3 "$ROOT/run_with_env.py" scripts/deepresearch/run_quality_eval.py --execute \
    --base-url http://127.0.0.1:8081 --timeout-seconds 3600 --dataset "$DATASET" --runs-dir "$OUT/stream$n" $args \
    >> "$OUT/stream$n.log" 2>&1 < /dev/null &
  echo "stream$n pid $!"
  # 两路错开启动，避免同时首次注册评测账号
  sleep 5
done < "$OUT/streams.txt"
