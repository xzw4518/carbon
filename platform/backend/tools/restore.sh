#!/usr/bin/env bash
# 恢复：MySQL 全量 + 上传文件
# 用法：DB_PASSWORD=xxx bash tools/restore.sh backup/carbonbal-20261125-120000.sql.gz [files-*.tar.gz]
set -euo pipefail
DUMP="${1:?请指定备份文件}"
FILES="${2:-}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-carbonbal}"
DB_USER="${DB_USER:-carbonbal}"
DB_PASSWORD="${DB_PASSWORD:?请通过环境变量提供 DB_PASSWORD}"
FILE_ROOT="${FILE_ROOT:-./data/files}"

echo "即将把 $DUMP 恢复到 $DB_HOST:$DB_PORT/$DB_NAME"
read -r -p "确认继续？(yes/no) " ok
[ "$ok" = "yes" ] || { echo "已取消"; exit 1; }

gunzip -c "$DUMP" | mysql --default-character-set=utf8mb4 -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME"
echo "数据库恢复完成"

if [ -n "$FILES" ]; then
  tar -xzf "$FILES" -C "$(dirname "$FILE_ROOT")"
  echo "文件恢复完成：$FILE_ROOT"
fi
echo "请重启服务并执行 bash tools/smoke.sh 验证"
