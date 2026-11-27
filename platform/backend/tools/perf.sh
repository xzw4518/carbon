#!/usr/bin/env bash
# M10 性能预算：接口耗时 / 快照体积 / 前端产物 / 首屏指标
# 用法：bash tools/perf.sh [BASE_URL]
# 退出码：0 全部在预算内；1 有超预算项
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
export MSYS_NO_PATHCONV=1

BASE="${1:-${BASE_URL:-http://localhost:8088}}"
STAMP="$(date +%F)"
mkdir -p target/inspect
LOG="target/inspect/perf-$STAMP.log"
exec > >(tee "$LOG") 2>&1

# ---- 预算（与 docs/performance-budget.md 保持一致）----
BUDGET_BOOT_KB=60        # /api/bootstrap 原始体积
BUDGET_BOOT_GZ_KB=20     # gzip 后体积
BUDGET_API_MS=300        # 关键接口中位耗时
BUDGET_ASSET_KB=1024     # 前端产物总量
BUDGET_FCP_MS=1200
BUDGET_LCP_MS=2000
BUDGET_REQ=30
BUDGET_TRANSFER_KB=300
BUDGET_DOM=1200

P=0; F=0
ck(){ # name actual budget unit
  local n="$1" a="$2" b="$3" u="$4"
  if [ "${a:-999999}" -le "$b" ]; then
    P=$((P+1)); printf '[OK]   %-26s %s%s ≤ %s%s\n' "$n" "$a" "$u" "$b" "$u"
  else
    F=$((F+1)); printf '[FAIL] %-26s %s%s > %s%s\n' "$n" "$a" "$u" "$b" "$u"
  fi
}

TMP="target/.perf-tmp"
rm -rf "$TMP"; mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT
JAR="$TMP/cookies"
# 密码登录已强制图形验证码：先取码再登录
CAP=$(curl -s "$BASE/api/auth/captcha")
CID=$(echo "$CAP" | python -c "import sys,json;print(json.load(sys.stdin)['body']['captchaId'])" 2>/dev/null)
CODE=$(echo "$CAP" | python -c "import sys,json,base64,re;d=json.load(sys.stdin);svg=base64.b64decode(d['body']['image'].split(',')[1]).decode();print(''.join(re.findall(r'<text[^>]*>([^<])</text>',svg)))" 2>/dev/null)
TOKEN=$(curl -s -c "$JAR" -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"13800000001\",\"password\":\"Admin@2026!Pass\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CODE\"}" |
  python -c "import sys,json;print(json.load(sys.stdin)['body']['token'])" 2>/dev/null)
if [ -z "$TOKEN" ]; then
  echo "[FAIL] 登录失败，无法采样"
  exit 1
fi
AUTH="Authorization: Bearer $TOKEN"

median(){ sort -n | awk '{a[NR]=$1} END{ if(NR==0){print 0}else{print a[int((NR+1)/2)]} }'; }

measure(){ # label path
  local label="$1" path="$2" times="" sizes=""
  local i out t s
  for i in 1 2 3 4 5; do
    out=$(curl -s -b "$JAR" -H "$AUTH" "$BASE$path" -o "$TMP/body" -w '%{time_total} %{size_download}')
    t=${out%% *}; s=${out##* }
    times="$times$(awk -v x="$t" 'BEGIN{printf "%.0f", x*1000}')
"
    sizes="$sizes$s
"
  done
  local ms=$(printf '%s' "$times" | median)
  local kb=$(( $(printf '%s' "$sizes" | median) / 1024 ))
  ck "$label" "$ms" "$BUDGET_API_MS" "ms"
  printf '       %s 体积 %sKB\n' "$label" "$kb"
}

echo "性能预算 · $(date '+%F %T') · $BASE"
echo "------------------------------------------------------------"
measure "bootstrap" "/api/bootstrap?year=2026"
measure "products 分页" "/api/products?page=1&size=20"
measure "overview" "/api/overview"
measure "analysis 能耗" "/api/analysis/energy-consumption?from=2026-09&to=2026-11"
measure "alert-records" "/api/alert-records?page=1&size=10"

BOOT=$(curl -s -b "$JAR" -H "$AUTH" "$BASE/api/bootstrap?year=2026" -o "$TMP/boot" -w '%{size_download}')
BOOT_GZ=$(curl -s -b "$JAR" -H "$AUTH" -H 'Accept-Encoding: gzip' "$BASE/api/bootstrap?year=2026" -o "$TMP/boot.gz" -w '%{size_download}')
ck "bootstrap 原始体积" "$((BOOT / 1024))" "$BUDGET_BOOT_KB" "KB"
ck "bootstrap gzip" "$((BOOT_GZ / 1024))" "$BUDGET_BOOT_GZ_KB" "KB"

ASSET_KB=$(( $(find ../../自研原型/index.html ../../自研原型/assets -type f -printf '%s\n' 2>/dev/null | awk '{s+=$1} END{print int(s/1024)}') ))
ck "前端产物" "$ASSET_KB" "$BUDGET_ASSET_KB" "KB"

# ---- 首屏（可选，需 playwright-core）----
UI_JSON=$(cd ../../自研原型 && node tools/perf-ui.cjs "$BASE" 2>/dev/null | tail -1)
if echo "$UI_JSON" | grep -q '"fcp"'; then
  get(){ echo "$UI_JSON" | python -c "import sys,json;print(json.load(sys.stdin).get('$1',0))" 2>/dev/null; }
  ck "首屏 FCP" "$(get fcp)" "$BUDGET_FCP_MS" "ms"
  ck "首屏 LCP" "$(get lcp)" "$BUDGET_LCP_MS" "ms"
  ck "首屏请求数" "$(get requests)" "$BUDGET_REQ" ""
  ck "首屏传输量" "$(get transferKB)" "$BUDGET_TRANSFER_KB" "KB"
  ck "首页 DOM 节点" "$(get domNodes)" "$BUDGET_DOM" ""
  ERR=$(get consoleErrors)
  [ "${ERR:-1}" -eq 0 ] && { P=$((P+1)); echo "[OK]   控制台错误               0"; } \
                        || { F=$((F+1)); echo "[FAIL] 控制台错误               $ERR"; }
else
  echo "[SKIP] 首屏指标（未安装 playwright-core 或浏览器不可用）"
fi

echo "------------------------------------------------------------"
echo "RESULT: $([ $F -eq 0 ] && echo PASS || echo FAIL) ($P 项达标, $F 项超预算)"
echo "日志：$LOG"
[ $F -eq 0 ]
