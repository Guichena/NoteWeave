#!/bin/sh
# 用法：build_images.sh <标签>；用 dev2 工作区构建 Worker 与后端镜像，构建后清空构建缓存（根分区只有 16G）
set -eu
TAG="$1"
SRC=/data/workspace/noteweave-research-dev2
cd "$SRC"
echo "HEAD $(git log --oneline -1)"
docker build -q -t "nw-research-worker:$TAG" workers/research-worker
docker build -q -t "nw-research-backend:$TAG" backend
docker builder prune -af >/dev/null
df -h / | tail -1
echo BUILD_DONE
