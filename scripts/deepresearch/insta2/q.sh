#!/bin/sh
# 用法：q.sh "<SQL>"；在 insta2 的 MySQL 容器内执行只读查询，口令只在容器内使用
docker exec -i noteweave-v2-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" noteweave --default-character-set=utf8mb4 -e "$0" 2>/dev/null' "$1"
