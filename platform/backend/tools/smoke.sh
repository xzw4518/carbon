#!/usr/bin/env bash
# 上线后冒烟：健康检查 + 带图形验证码登录 + 关键只读接口 + 前端可达
#
# 用法：bash tools/smoke.sh [BASE_URL]
#   环境变量：SMOKE_USER / SMOKE_PWD 覆盖默认管理员账号
# 退出码：0 = 全部通过；非 0 = 有失败（任何一项失败即非 0，便于部署流水线拦截）
#
# 历史缺陷（P1-7）：密码登录改造为"必须带图形验证码"后，本脚本仍只发 phone/password，
# 导致脚本永久 400 —— 上线冒烟形同虚设。现在统一复用 tools/lib-auth.sh 的 cap_token/login。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
BASE="${1:-http://127.0.0.1:8088}"
export BASE
# shellcheck source=tools/lib-auth.sh
. tools/lib-auth.sh

SMOKE_USER="${SMOKE_USER:-13800000001}"
SMOKE_PWD="${SMOKE_PWD:-Admin@2026!Pass}"

PASS=0; FAIL=0
ok(){ echo "[PASS] $1"; PASS=$((PASS+1)); }
bad(){ echo "[FAIL] $1 — $2"; FAIL=$((FAIL+1)); }

H=$(curl -s --max-time 5 "$BASE/api/health")
echo "$H" | grep -q '"status":"UP"' && ok "健康检查 UP" || bad "健康检查" "${H:-无响应}"

T=$(login "$SMOKE_USER" "$SMOKE_PWD")
[ -n "$T" ] && ok "管理员登录（含图形验证码）" || bad "管理员登录" "无 token"
[ -z "$T" ] && { echo; echo "RESULT: FAIL ($PASS passed, $FAIL failed)"; exit 1; }

# 用户名兼容入口（回滚场景会用旧账号名登录）
T2=$(login_username admin "$SMOKE_PWD")
[ -n "$T2" ] && echo "[INFO] 用户名兼容入口可用（不计入 9 项）" || echo "[WARN] 用户名兼容入口不可用"

for p in /api/overview /api/products /api/devices /api/analysis/energy-consumption /api/quota-years /api/jobs; do
  C=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$BASE$p" -H "Authorization: Bearer $T")
  [ "$C" = "200" ] && ok "GET $p" || bad "GET $p" "HTTP $C"
done

APP=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$BASE/app/index.html")
[ "$APP" = "200" ] && ok "前端可访问" || bad "原型前端" "HTTP $APP"

echo
echo "RESULT: $( [ "$FAIL" -eq 0 ] && echo PASS || echo FAIL ) ($PASS passed, $FAIL failed)"
[ "$FAIL" -eq 0 ]
