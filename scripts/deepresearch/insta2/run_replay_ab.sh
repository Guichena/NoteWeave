#!/bin/bash
# 用 Worker 镜像起临时容器重放成文（新旧两版配对），环境变量取自评测 Worker 容器，不落盘；之后在宿主机评分
# 用法：[RUN=名称] [OLD=旧版模块文件名] run_replay_ab.sh <镜像标签> <新版模块文件名> <输入文件名>...（文件都在 exp 目录）
# RUN 区分同时进行的多组对照的输出文件，默认 ab；给了 OLD 时两版各自调用模型（用于比较提示改动）
TAG="$1"; NEW="$2"; shift 2
RUN="${RUN:-ab}"
EXP=/data/workspace/noteweave-research-eval/exp
for input in "$@"; do
  name=$(basename "$input" .json)
  echo "$(date -u +%H:%M:%S) replay $name" >> "$EXP/replay_$RUN.status"
  docker run --rm --name "nw-replay-$RUN-$name" --memory 1g --network nw-research_default \
    --env-file <(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' noteweave-v2-research-worker-consumer) \
    -e PYTHONPATH=/app -w /app -v "$EXP:/exp" --entrypoint python "nw-research-worker:$TAG" \
    /exp/replay_ab.py "/exp/$input" "/exp/$NEW" "/exp/replay_${RUN}_$name.json" 4 ${OLD:+"/exp/$OLD"} \
    > "$EXP/replay_${RUN}_$name.out" 2>&1
  echo "$(date -u +%H:%M:%S) judge $name" >> "$EXP/replay_$RUN.status"
  cd /data/workspace/noteweave-research-eval && python3 run_with_env.py exp/judge_ab.py "exp/replay_${RUN}_$name.json" \
    "exp/judge_${RUN}_$name.json" 4 > "$EXP/judge_${RUN}_$name.out" 2>&1
done
echo "$(date -u +%H:%M:%S) AB_DONE" >> "$EXP/replay_$RUN.status"
