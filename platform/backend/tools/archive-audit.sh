#!/usr/bin/env bash
# 审计日志归档：超过保留期的 audit_log 搬到 audit_log_archive
# 用法：
#   bash tools/archive-audit.sh                 # 预演（只统计）
#   bash tools/archive-audit.sh --apply         # 归档并删除热表数据
#   bash tools/archive-audit.sh --days 365 --apply
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
# shellcheck source=tools/lib-db.sh
. tools/lib-db.sh

DAYS="${AUDIT_RETENTION_DAYS:-180}"
APPLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1; shift ;;
    --days) DAYS="${2:?}"; shift 2 ;;
    *) echo "未知参数 $1"; exit 2 ;;
  esac
done

db_ready || { echo "SKIP 数据库不可达"; exit 0; }
case "$DAYS" in (*[!0-9]*|'') echo "--days 必须是正整数"; exit 2 ;; esac

CUTOFF="date_sub(now(), interval $DAYS day)"
HOT=$(sql_scalar "select count(*) from audit_log where logged_at < $CUTOFF")
OLDEST=$(sql_scalar "select coalesce(min(logged_at),'-') from audit_log")
ARCH=$(sql_scalar "select count(*) from audit_log_archive")

echo "== 审计归档 =="
echo "保留期      $DAYS 天"
echo "热表最早    $OLDEST"
echo "待归档      $HOT 条"
echo "已归档      $ARCH 条"

if [ "$HOT" = "0" ]; then
  echo "RESULT: NOTHING（无需归档）"
  exit 0
fi

if [ $APPLY -eq 0 ]; then
  echo
  echo "预演模式：未改动数据。确认后加 --apply 执行。"
  exit 0
fi

sql_exec "
insert into audit_log_archive
  (id, entity, entity_id, action, before_json, after_json, actor, logged_at, org_id, action_label, actor_id, archived_at)
select id, entity, entity_id, action, before_json, after_json, actor, logged_at, org_id, action_label, actor_id, now()
from audit_log where logged_at < $CUTOFF;
delete from audit_log where logged_at < $CUTOFF;
" >/dev/null

LEFT=$(sql_scalar "select count(*) from audit_log where logged_at < $CUTOFF")
NOWARCH=$(sql_scalar "select count(*) from audit_log_archive")
echo "归档后      $NOWARCH 条（本次 +$((NOWARCH - ARCH))）"
if [ "$LEFT" = "0" ]; then
  echo "RESULT: OK（热表已无超期数据）"
else
  echo "RESULT: LEFT $LEFT（仍有超期数据，检查权限或锁）"
  exit 1
fi
