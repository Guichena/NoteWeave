#!/bin/sh
# 用法：qraw.sh "<SQL>"；只读查询，不输出表头，字段内容不转义（便于直接解析 JSON 列）
docker exec -i noteweave-v2-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" noteweave --default-character-set=utf8mb4 -N -B --raw -e "$0" 2>/dev/null' "$1"
