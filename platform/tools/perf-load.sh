#!/usr/bin/env bash
# 并发压测骨架（P2-7）：只依赖 curl + python，不引入 k6/JMeter。
#
# ⚠️ 本脚本是"可执行的骨架"：结构与指标口径已定，但**未在联网/独立压测环境实测过**，
#    报告里必须标注"未实测"。正式压测（k6、24h 长稳、故障注入）登记 v1.1 发布前动作。
#
# 用法：
#   bash platform/tools/perf-load.sh [并发数=20] [每并发请求数=10] [BASE=http://127.0.0.1:8088]
#
# 产出：
#   每个路径的 请求数 / 失败数 / 平均耗时 / P95 耗时（ms）
#   失败率 > 0 或 P95 超过阈值时退出码 1（阈值见 P95_LIMIT_MS）
set -uo pipefail

CONCURRENCY="${1:-20}"
PER_WORKER="${2:-10}"
BASE="${3:-${BASE:-http://127.0.0.1:8088}}"
P95_LIMIT_MS="${P95_LIMIT_MS:-800}"

command -v curl >/dev/null || { echo "[FATAL] 需要 curl"; exit 2; }
command -v python >/dev/null || { echo "[FATAL] 需要 python"; exit 2; }

. "$(cd "$(dirname "$0")/../backend" && pwd)/tools/lib-auth.sh"
export BASE

TOKEN=$(login "${PERF_USER:-13800000001}" "${PERF_PWD:-Admin@2026!Pass}")
[ -n "$TOKEN" ] || { echo "[FATAL] 登录失败：先起服务并确认演示账号"; exit 2; }

PATHS=(
  "/api/health"
  "/api/bootstrap?year=2026"
  "/api/products?size=20"
  "/api/analysis/energy-consumption?year=2026"
  "/api/accounting-periods?year=2026"
)

TMPDIR_RUN="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_RUN"' EXIT

echo "== 并发压测骨架 =="
echo "并发 $CONCURRENCY × 每并发 $PER_WORKER 次，目标 $BASE，P95 阈值 ${P95_LIMIT_MS}ms"
echo

FAILED=0
for p in "${PATHS[@]}"; do
  OUT="$TMPDIR_RUN/$(echo "$p" | tr '/?=&' '____').txt"
  : > "$OUT"
  WORKERS=()
  for i in $(seq 1 "$CONCURRENCY"); do
    (
      for j in $(seq 1 "$PER_WORKER"); do
        curl -s -o /dev/null -w '%{http_code} %{time_total}\n' --max-time 10 \
          "$BASE$p" -H "Authorization: Bearer $TOKEN" >> "$OUT"
      done
    ) &
    WORKERS+=($!)
  done
  for w in "${WORKERS[@]}"; do wait "$w"; done

  python - "$OUT" "$p" "$P95_LIMIT_MS" <<'PY'
import sys, statistics
path, name, limit = sys.argv[1], sys.argv[2], float(sys.argv[3])
rows = [l.split() for l in open(path, encoding='utf-8') if l.strip()]
if not rows:
    print('[SKIP] %-42s 无样本' % name); sys.exit(0)
codes = [r[0] for r in rows]
ms = sorted(float(r[1]) * 1000 for r in rows)
bad = sum(1 for c in codes if not c.startswith('2'))
p95 = ms[min(len(ms) - 1, int(len(ms) * 0.95))]
avg = statistics.fmean(ms)
status = 'PASS' if bad == 0 and p95 <= limit else 'FAIL'
print('[%s] %-42s n=%d fail=%d avg=%.1fms p95=%.1fms' % (status, name, len(ms), bad, avg, p95))
sys.exit(0 if status == 'PASS' else 1)
PY
  [ $? -ne 0 ] && FAILED=$((FAILED+1))
done

echo
if [ "$FAILED" -gt 0 ]; then
  echo "RESULT: FAIL ($FAILED 个路径未达标)"
  exit 1
fi
echo "RESULT: PASS"
echo "注意：本脚本是骨架，未在独立压测环境实测；k6/24h 长稳登记 v1.1。"
