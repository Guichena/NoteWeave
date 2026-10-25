#!/bin/sh
# 用法：build_worker_only.sh <新标签> <后端沿用的标签>；只有 Worker 改动时只构建 Worker，后端镜像打新标签沿用
set -eu
TAG="$1"; BACKEND_FROM="$2"
DEPLOY=/data/workspace/noteweave-research-deploy
cd /data/workspace/noteweave-research-dev2
{
  echo "HEAD $(git log --oneline -1)"
  docker build -q -t "nw-research-worker:$TAG" workers/research-worker
  docker tag "nw-research-backend:$BACKEND_FROM" "nw-research-backend:$TAG"
  docker builder prune -af >/dev/null
  df -h / | tail -1
  sed "s/$BACKEND_FROM/$TAG/g" "$DEPLOY/compose.$BACKEND_FROM.json" > "$DEPLOY/compose.$TAG.json"
  echo BUILD_DONE
} > "$DEPLOY/build-$TAG.log" 2>&1
