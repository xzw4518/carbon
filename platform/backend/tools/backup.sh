#!/usr/bin/env bash
# 备份：MySQL 全量 + 上传文件目录；保留 N 天
# 用法：DB_PASSWORD=xxx bash tools/backup.sh [备份目录] [保留天数]
set -euo pipefail
OUT="${1:-./backup}"
KEEP="${2:-14}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-carbonbal}"
DB_USER="${DB_USER:-carbonbal}"
DB_PASSWORD="${DB_PASSWORD:?请通过环境变量提供 DB_PASSWORD}"
FILE_ROOT="${FILE_ROOT:-./data/files}"
STAMP=$(date +%Y%m%d-%H%M%S)

mkdir -p "$OUT"
DUMP="$OUT/carbonbal-$STAMP.sql.gz"
if command -v mysqldump >/dev/null 2>&1; then
  mysqldump -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" \
    --single-transaction --routines --triggers --no-tablespaces \
    --default-character-set=utf8mb4 --hex-blob --set-gtid-purged=OFF "$DB_NAME" | gzip > "$DUMP"
else
  echo "找不到 mysqldump，请安装 mysql-client" >&2
  exit 1
fi
echo "SQL  备份完成：$DUMP ($(du -h "$DUMP" | cut -f1))"

if [ -d "$FILE_ROOT" ]; then
  TAR="$OUT/files-$STAMP.tar.gz"
  tar -czf "$TAR" -C "$(dirname "$FILE_ROOT")" "$(basename "$FILE_ROOT")"
  echo "文件 备份完成：$TAR ($(du -h "$TAR" | cut -f1))"
fi

find "$OUT" -name 'carbonbal-*.sql.gz' -mtime +"$KEEP" -delete
find "$OUT" -name 'files-*.tar.gz' -mtime +"$KEEP" -delete
echo "已清理 $KEEP 天前的备份"
