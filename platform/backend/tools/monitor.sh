#!/usr/bin/env bash
# 监控探针：健康检查 + 关键接口，异常返回非 0（可挂 cron / 告警平台）
# 用法：bash tools/monitor.sh [BASE_URL] [TOKEN]
set -uo pipefail
BASE="${1:-http://127.0.0.1:8088}"
TOKEN="${2:-${ADMIN_TOKEN:-}}"
FAIL=0

H=$(curl -s -m 5 "$BASE/api/health" || true)
echo "$H" | grep -q '"status":"UP"' || { echo "CRITICAL 健康检查失败: $H"; FAIL=1; }
echo "$H" | grep -q '"db":"UP"' || { echo "CRITICAL 数据库不可用: $H"; FAIL=1; }

CODE=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/app/index.html" || true)
[ "$CODE" = "200" ] || { echo "WARN 前端入口异常: $CODE"; FAIL=1; }

if [ -n "$TOKEN" ]; then
  for p in /api/overview /api/products; do
    C=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE$p" -H "Authorization: Bearer $TOKEN")
    [ "$C" = "200" ] || { echo "WARN $p -> $C"; FAIL=1; }
  done
fi

[ "$FAIL" -eq 0 ] && echo "OK 全部探针正常"
exit "$FAIL"
