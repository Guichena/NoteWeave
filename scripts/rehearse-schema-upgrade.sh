#!/usr/bin/env bash
# 迁移预演：把一份真实库的 dump 载入一次性 MySQL，再按顺序把尚未应用的迁移打上去。
#
# 用途：发布前确认「当前库 → 目标版本」这条路径能走通，不改动任何共享环境。
#
# 用法：
#   scripts/rehearse-schema-upgrade.sh <dump.sql> [已应用到的版本，默认从 dump 的 flyway_schema_history 读]
#
# 前置：本机有 docker；dump 用 mysqldump --single-transaction 导出。
# 注意：容器必须带 --collation-server=utf8mb4_unicode_ci，与 docker-compose.yml 一致——
# 用 MySQL 8 默认的 utf8mb4_0900_ai_ci 建库，新建表与旧表排序规则不同，外键会报 3780 类型不兼容，
# 那是预演环境的问题，不是迁移的问题。
set -euo pipefail

DUMP=${1:?用法: rehearse-schema-upgrade.sh <dump.sql> [当前版本]}
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
MIGRATIONS="$REPO_ROOT/backend/src/main/resources/db/migration"
CONTAINER=nw-upgrade-rehearsal
PORT=${REHEARSAL_PORT:-13399}
PASSWORD=${REHEARSAL_PASSWORD:-rehearsal}

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "== 启动一次性 MySQL（utf8mb4_unicode_ci）"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" \
  -e MYSQL_ROOT_PASSWORD="$PASSWORD" -e MYSQL_DATABASE=noteweave \
  -p "127.0.0.1:$PORT:3306" mysql:8.4 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci >/dev/null

for _ in $(seq 1 90); do
  if docker exec "$CONTAINER" mysql -uroot -p"$PASSWORD" -N \
      -e "select count(*) from information_schema.schemata where schema_name='noteweave';" 2>/dev/null | grep -q '^1$'; then
    break
  fi
  sleep 1
done

# dump 里可能带有 definer 视图/触发器，先建同名账号，否则 1449
docker exec "$CONTAINER" mysql -uroot -p"$PASSWORD" \
  -e "create user if not exists 'noteweave'@'%' identified by '$PASSWORD'; grant all on *.* to 'noteweave'@'%';" 2>/dev/null

echo "== 载入 dump: $DUMP"
docker exec -i "$CONTAINER" mysql -uroot -p"$PASSWORD" noteweave < "$DUMP"

CURRENT=${2:-$(docker exec "$CONTAINER" mysql -uroot -p"$PASSWORD" -N \
  -e "select ifnull(max(version),0) from noteweave.flyway_schema_history where success=1;" 2>/dev/null)}
echo "== 当前版本: $CURRENT"

echo "== 按序应用更高版本的迁移"
applied=0
failed=0
for file in $(ls "$MIGRATIONS"/V*.sql | sort -V); do
  version=$(basename "$file" | sed 's/^V//; s/__.*//')
  if [ "$version" -le "$CURRENT" ]; then continue; fi
  # 注释按 Flyway 的做法剥掉：MySQL 要求 `-- ` 后带空白，个别迁移写成了 `--（`
  if sed 's/--.*$//' "$file" | docker exec -i "$CONTAINER" mysql -uroot -p"$PASSWORD" noteweave 2>/tmp/rehearse.err; then
    applied=$((applied + 1))
  else
    failed=$((failed + 1))
    echo "FAIL $(basename "$file"): $(grep -av 'Using a password' /tmp/rehearse.err | head -2)"
  fi
done

echo "== 结果: 应用 $applied 个，失败 $failed 个"
echo "== 表数量: $(docker exec "$CONTAINER" mysql -uroot -p"$PASSWORD" -N -e "select count(*) from information_schema.tables where table_schema='noteweave';" 2>/dev/null)"
[ "$failed" -eq 0 ]
