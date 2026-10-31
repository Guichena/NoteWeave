#!/bin/sh
# 用法：deploy.sh <覆盖文件名>；叠加 compose.research.json 与指定覆盖文件，只重建 backend 与 research worker
set -eu
D=/data/workspace/noteweave-research-deploy
OVERRIDE="$D/$1"
test -f "$OVERRIDE"
# 重建前存档当前容器日志，评测结束后还要按日志排查失败原因
mkdir -p "$D/logs"
STAMP=$(date -u +%Y%m%d-%H%M%S)
for c in noteweave-v2-backend noteweave-v2-research-worker-consumer; do
  docker logs "$c" > "$D/logs/$STAMP-$c.log" 2>&1 || true
done
gzip -f "$D/logs/$STAMP-"*.log 2>/dev/null || true
docker compose -p nw-research --project-directory /data/workspace/noteweave-research --env-file "$D/.env" \
  -f /data/workspace/noteweave-research/docker-compose.yml -f "$D/compose.instabox.json" -f "$D/compose.release.json" \
  -f "$D/compose.research.json" -f "$OVERRIDE" --profile app up -d --no-build --force-recreate backend research-worker-consumer
for i in $(seq 1 60); do
  b=$(docker inspect -f '{{.State.Health.Status}}' noteweave-v2-backend 2>/dev/null || echo none)
  w=$(docker inspect -f '{{.State.Health.Status}}' noteweave-v2-research-worker-consumer 2>/dev/null || echo none)
  if [ "$b" = healthy ] && [ "$w" = healthy ]; then
    docker inspect noteweave-v2-backend noteweave-v2-research-worker-consumer --format '{{.Name}} {{.Config.Image}}'
    exit 0
  fi
  sleep 5
done
echo "UNHEALTHY backend=$b worker=$w"
exit 1
