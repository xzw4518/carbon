#!/usr/bin/env bash
# 运维巡检：日 / 周 / 月三档（只读，不改数据）
# 用法：
#   bash tools/inspect.sh            # 日巡检
#   bash tools/inspect.sh --weekly   # 周巡检（含数据一致性门禁）
#   bash tools/inspect.sh --monthly  # 月巡检（容量/席位/到期）
# 退出码：0 正常；1 有 FAIL；WARN 不影响退出码
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
# shellcheck source=tools/lib-db.sh
. tools/lib-db.sh

MODE="daily"
case "${1:-}" in
  --weekly) MODE="weekly" ;;
  --monthly) MODE="monthly" ;;
  --daily|"") MODE="daily" ;;
  *) echo "用法：bash tools/inspect.sh [--daily|--weekly|--monthly]"; exit 2 ;;
esac

BASE="${BASE_URL:-http://127.0.0.1:8088}"
BACKUP_DIR="${BACKUP_DIR:-./backup}"
STAMP="$(date +%F)"
mkdir -p target/inspect
LOG="target/inspect/$MODE-$STAMP.log"
exec > >(tee "$LOG") 2>&1

P=0; W=0; F=0
ck(){ # ok|warn|fail 名称 详情
  case "$1" in
    ok) P=$((P+1)); printf '[OK]   %-24s %s\n' "$2" "${3:-}" ;;
    warn) W=$((W+1)); printf '[WARN] %-24s %s\n' "$2" "${3:-}" ;;
    fail) F=$((F+1)); printf '[FAIL] %-24s %s\n' "$2" "${3:-}" ;;
  esac
}
q(){ sql_scalar "$1"; }

echo "巡检模式 $MODE · $(date '+%F %T') · $DB_HOST:$DB_PORT/$DB_NAME"
echo "------------------------------------------------------------"

if ! db_ready; then
  ck fail "数据库连通" "无法连接 $DB_HOST:$DB_PORT/$DB_NAME"
  echo; echo "RESULT: FAIL ($F failed)"; exit 1
fi

# ---------- 日巡检：可用性 / 当日风险 ----------
H=$(curl -s -m 5 "$BASE/api/health" || true)
echo "$H" | grep -q '"status":"UP"' && ck ok "服务健康" "UP" || ck fail "服务健康" "$H"
echo "$H" | grep -q '"db":"UP"' && ck ok "数据库探针" "UP" || ck fail "数据库探针" "DOWN"
APP=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/app/index.html" || true)
[ "$APP" = "200" ] && ck ok "前端入口" "200" || ck fail "前端入口" "$APP"

SIZE=$(q "select round(sum(data_length+index_length)/1024/1024,1) from information_schema.tables where table_schema=database()")
TB=$(q "select count(*) from information_schema.tables where table_schema=database()")
ck ok "库容量" "${SIZE:-?} MB / ${TB:-?} 表"

LOCKED=$(q "select count(*) from app_user where locked_until is not null and locked_until > now()")
[ "${LOCKED:-0}" -gt 0 ] && ck warn "锁定账号" "$LOCKED 个（15 分钟后自动解锁）" || ck ok "锁定账号" "0"

BADLOG=$(q "select count(*) from login_log where result in ('bad_password','bad_code') and at >= date_sub(now(), interval 1 day)")
[ "${BADLOG:-0}" -gt 100 ] && ck warn "24h 登录失败" "$BADLOG 次（排查撞库）" || ck ok "24h 登录失败" "${BADLOG:-0} 次"

OPEN=$(q "select count(*) from alert_record where status='open'")
[ "${OPEN:-0}" -gt 20 ] && ck warn "未处理预警" "$OPEN 条" || ck ok "未处理预警" "${OPEN:-0} 条"
UNREAD=$(q "select count(*) from alert_record where is_read=0")
ck ok "未读预警" "${UNREAD:-0} 条"

BK=$(find "$BACKUP_DIR" -maxdepth 1 -name 'carbonbal-*.sql.gz' -type f 2>/dev/null | wc -l | tr -d ' ')
if [ "${BK:-0}" -eq 0 ]; then
  ck warn "备份文件" "$BACKUP_DIR 下没有备份（确认定时任务）"
else
  NEWEST=$(find "$BACKUP_DIR" -maxdepth 1 -name 'carbonbal-*.sql.gz' -type f -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1)
  AGEH=$(( ( $(date +%s) - ${NEWEST%%.*} ) / 3600 ))
  BKSIZE=$(du -h "${NEWEST#* }" 2>/dev/null | cut -f1)
  [ "$AGEH" -le 26 ] && ck ok "备份新鲜度" "${AGEH}h 前 / $BKSIZE / 共 $BK 份" \
                      || ck warn "备份新鲜度" "最新 ${AGEH}h 前，超过 26h"
fi

TODAY_AUDIT=$(q "select count(*) from audit_log where logged_at >= curdate()")
ck ok "今日审计" "${TODAY_AUDIT:-0} 条"

# ---------- 周巡检：数据质量 / 积压 ----------
if [ "$MODE" = "weekly" ]; then
  echo "------------------------------------------------------------"
  bash tools/data-check.sh --quiet > target/inspect/data-check.txt 2>&1
  DC=$?
  if [ $DC -eq 0 ]; then
    ck ok "数据一致性" "$(tail -1 target/inspect/data-check.txt)"
  else
    ck fail "数据一致性" "$(tail -1 target/inspect/data-check.txt)（详见 target/inspect/data-check.txt）"
  fi

  bash tools/check-docs.sh > target/inspect/check-docs.txt 2>&1
  if [ $? -eq 0 ]; then
    ck ok "文档一致性" "$(tail -1 target/inspect/check-docs.txt)"
  else
    ck fail "文档一致性" "$(tail -1 target/inspect/check-docs.txt)"
  fi

  BACKLOG=$(q "select count(*) from audit_log where logged_at < date_sub(now(), interval 180 day)")
  [ "${BACKLOG:-0}" -gt 0 ] && ck warn "审计积压" "$BACKLOG 条超 180 天（执行 archive-audit.sh）" || ck ok "审计积压" "0"

  for t in report device_reading audit_log accounting_line login_log; do
    ck ok "表行数 $t" "$(q "select count(*) from $t")"
  done

  WEEK_NEW=$(q "select count(*) from app_user where created_at >= date_sub(now(), interval 7 day)")
  ck ok "7 日新增账号" "${WEEK_NEW:-0}"
fi

# ---------- 月巡检：容量 / 席位 / 到期 ----------
if [ "$MODE" = "monthly" ]; then
  echo "------------------------------------------------------------"
  echo "租户席位（前 5）"
  sql "select o.name, coalesce(o.seat_limit,0), count(m.id)
       from org o left join user_tenant m on m.org_id=o.id and m.status='active'
       group by o.id, o.name, o.seat_limit order by count(m.id) desc limit 5" |
    while IFS=$'\t' read -r n lim used; do
      [ -z "$n" ] && continue
      if [ "$lim" -gt 0 ] && [ "$used" -ge "$lim" ]; then
        printf '  [WARN] %-24s %s/%s（已满）\n' "$n" "$used" "$lim"
      elif [ "$lim" -gt 0 ]; then
        printf '  [OK]   %-24s %s/%s\n' "$n" "$used" "$lim"
      else
        printf '  [OK]   %-24s %s/不限\n' "$n" "$used"
      fi
    done

  EXP=$(q "select count(*) from org where expire_at is not null and expire_at between now() and date_add(now(), interval 30 day)")
  [ "${EXP:-0}" -gt 0 ] && ck warn "30 日内到期租户" "$EXP 个" || ck ok "30 日内到期租户" "0"

  echo "近 6 个月审计量"
  sql "select date_format(logged_at,'%Y-%m'), count(*) from audit_log
       where logged_at >= date_sub(now(), interval 6 month)
       group by 1 order by 1" |
    while IFS=$'\t' read -r m n; do [ -n "$m" ] && printf '  %-8s %s\n' "$m" "$n"; done

  echo "最大 5 张表"
  sql "select table_name, round((data_length+index_length)/1024/1024,1)
       from information_schema.tables where table_schema=database()
       order by (data_length+index_length) desc limit 5" |
    while IFS=$'\t' read -r t mb; do [ -n "$t" ] && printf '  %-24s %s MB\n' "$t" "$mb"; done

  ck ok "报告总数" "$(q "select count(*) from report")"
  ck ok "核算期间" "$(q "select count(*) from accounting_period")"
fi

echo "------------------------------------------------------------"
echo "RESULT: $([ $F -eq 0 ] && echo OK || echo FAIL) ($P ok, $W warn, $F fail)"
echo "日志：$LOG"
[ $F -eq 0 ]
