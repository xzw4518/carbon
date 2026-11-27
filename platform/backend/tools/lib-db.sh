#!/usr/bin/env bash
# 共用：定位 mysql 客户端 + 数据库连接参数（供 data-check / inspect / e2e 清理使用）
# 参数全部走环境变量，默认值与本地开发库一致；生产巡检时显式覆盖。
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3307}"
DB_NAME="${DB_NAME:-carbonbal}"
DB_USER="${DB_USER:-carbonbal}"
DB_PASSWORD="${DB_PASSWORD:-CarbonBal@2026}"

_mysql_client() {
  if [ -n "${MYSQL_BIN:-}" ] && [ -x "${MYSQL_BIN}" ]; then printf '%s' "$MYSQL_BIN"; return 0; fi
  if command -v mysql >/dev/null 2>&1; then command -v mysql; return 0; fi
  local c
  for c in "D:/dev-tools/mysql-8.0.28-winx64/bin/mysql.exe" \
           "/d/dev-tools/mysql-8.0.28-winx64/bin/mysql.exe" \
           "/usr/bin/mysql" "/usr/local/bin/mysql" "/opt/homebrew/bin/mysql"; do
    [ -x "$c" ] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

MYSQL_BIN_PATH="$(_mysql_client || true)"

# sql "<query>" → 单列多行，制表符分隔；失败返回非 0
sql() {
  [ -n "$MYSQL_BIN_PATH" ] || return 1
  "$MYSQL_BIN_PATH" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
    --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null
}

# sql_scalar "<query>" → 第一行第一列
sql_scalar() {
  sql "$1" | head -1 | tr -d '\r'
}

# sql_exec "<statements>" → 执行写操作（多语句）
sql_exec() {
  [ -n "$MYSQL_BIN_PATH" ] || return 1
  "$MYSQL_BIN_PATH" -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
    --default-character-set=utf8mb4 -e "$1" 2>/dev/null
}

db_ready() {
  [ -n "$MYSQL_BIN_PATH" ] && [ "$(sql_scalar 'select 1')" = "1" ]
}
