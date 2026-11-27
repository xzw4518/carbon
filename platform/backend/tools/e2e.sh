#!/usr/bin/env bash
# 端到端验收（真实 HTTP）：登录 → 核算 → 报告 → 提交/锁定 → 拦截 → 解锁 → 审计
# 用法：bash tools/e2e.sh [BASE_URL]
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
# shellcheck source=tools/lib-db.sh
. tools/lib-db.sh
BASE="${1:-http://127.0.0.1:8088}"
PERIOD="2026-11"
PASS=0; FAIL=0; SKIP=0
ok(){ echo "[PASS] $1"; PASS=$((PASS+1)); }
bad(){ echo "[FAIL] $1 — $2"; FAIL=$((FAIL+1)); }
# 跳过不等于通过：结论里单列 skipped，不参与 PASS 计数（P1-8）
skip(){ echo "[SKIP] $1 — $2"; SKIP=$((SKIP+1)); }

# 图形验证码解析 / 登录：与 smoke.sh 共用同一份实现（P1-7），不再各写一套
export BASE
# shellcheck source=tools/lib-auth.sh
. tools/lib-auth.sh

ADMIN=$(login 13800000001 'Admin@2026!Pass')
FILLER=$(login 13800000002 'Test@2026!Pass')
REVIEWER=$(login 13800000004 'Test@2026!Pass')
APPROVER=$(login 13800000005 'Test@2026!Pass')
VIEWER=$(login 13800000003 'Test@2026!Pass')
[ -n "$ADMIN" ] && ok "手机号登录：管理员拿到 token" || bad "登录 管理员" "无 token"
[ -n "$FILLER" ] && ok "手机号登录：填报员拿到 token" || bad "登录 填报员" "无 token"
[ -n "$REVIEWER" ] && [ -n "$APPROVER" ] && ok "复核人与批准人账号可用" || bad "复核/批准账号" "无 token"

# 旧用户名入口保留一版，验证兼容
LEGACY=$(login_username admin 'Admin@2026!Pass')
[ -n "$LEGACY" ] && ok "用户名登录兼容一版（回滚用）" || bad "用户名兼容" "无 token"

# 旧会话 Cookie 失效后，仍必须能重新登录（JwtAuthFilter 匿名白名单回归）
CAPC=$(cap_token); CAPID=${CAPC%%|*}; CAPCODE=${CAPC##*|}
STALE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' -H 'Cookie: cb_token=stale.invalid.token' \
  -d "{\"phone\":\"13800000001\",\"password\":\"Admin@2026!Pass\",\"captchaId\":\"$CAPID\",\"captchaCode\":\"$CAPCODE\"}")
[ "$STALE" = "200" ] && ok "旧会话 Cookie 下仍可重新登录" || bad "旧会话登录" "得到 $STALE"

# 自愈：上次中断可能把 2026-11 留在 locked，先解锁再重跑
PSTATE=$(curl -s "$BASE/api/accounting-periods/$PERIOD" -H "Authorization: Bearer $ADMIN" | jget "d['body']['status']")
if [ "$PSTATE" = "locked" ]; then
  PV0=$(curl -s "$BASE/api/accounting-periods/$PERIOD" -H "Authorization: Bearer $ADMIN" | jget "d['body']['version']")
  curl -s -X POST "$BASE/api/accounting-periods/$PERIOD/unlock" -H "Authorization: Bearer $ADMIN" \
    -H 'Content-Type: application/json' -d "{\"version\":$PV0,\"reason\":\"TST 重跑前重置\"}" > /dev/null
  echo "[INFO] 检测到 2026-11 已锁定，已解锁后重跑"
fi

CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/devices")
[ "$CODE" = "401" ] && ok "无 token 访问返回 401" || bad "未授权拦截" "得到 $CODE"

DEVS=$(curl -s "$BASE/api/devices" -H "Authorization: Bearer $FILLER")
N=$(echo "$DEVS" | jget "len(d['body'])")
[ "$N" -ge 6 ] && ok "设备列表 $N 台" || bad "设备列表" "只有 $N"

# ---------- 用户体系：成员管理 ----------
UL=$(curl -s "$BASE/api/users" -H "Authorization: Bearer $ADMIN")
UN=$(echo "$UL" | jget "len(d['body'])")
[ "$UN" -ge 5 ] && ok "成员列表 $UN 人（含复核人/批准人）" || bad "成员列表" "只有 $UN"
UFORB=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/users" -H "Authorization: Bearer $FILLER")
[ "$UFORB" = "403" ] && ok "填报员不能管理成员（403）" || bad "成员权限" "得到 $UFORB"
TS=$(date +%H%M%S)
TST_PHONES=""; TST_PV_IDS=""; TST_REPORT_IDS=""; TST_QID=""
track_phone(){ TST_PHONES="${TST_PHONES:+$TST_PHONES,}$1"; }
track_report(){ TST_REPORT_IDS="${TST_REPORT_IDS:+$TST_REPORT_IDS,}$1"; }
cleanup_test_data(){
  [ -n "${TS:-}" ] || return 0
  [ -n "$MYSQL_BIN_PATH" ] || return 0
  bash tools/purge-test-data.sh --marker "$TS" --phones "${TST_PHONES:-}" --apply >/dev/null 2>&1 || true
  if [ -n "${TST_PV_IDS:-}" ]; then
    sql_exec "delete from production_value where id in ($TST_PV_IDS);" >/dev/null 2>&1 || true
  fi
  if [ -n "${TST_QID:-}" ]; then
    sql_exec "delete from quota_year where id = $TST_QID;" >/dev/null 2>&1 || true
  fi
  if [ -n "${TST_REPORT_IDS:-}" ]; then
    sql_exec "
              delete from report where id in ($TST_REPORT_IDS);" >/dev/null 2>&1 || true
  fi
}
cleanup_on_exit(){ local rc=$?; trap - EXIT INT TERM; cleanup_test_data; exit $rc; }
trap cleanup_on_exit EXIT INT TERM
NEWPHONE="1390000$(printf '%04d' $((10#$TS % 10000)))"
track_phone "$NEWPHONE"
NEW=$(curl -s -X POST "$BASE/api/users" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$NEWPHONE\",\"displayName\":\"TST 成员\",\"role\":\"viewer\"}")
MID=$(echo "$NEW" | jget "d['body']['membershipId']")
TMPPW=$(echo "$NEW" | jget "d['body']['tempPassword']")
[ -n "$MID" ] && [ -n "$TMPPW" ] && ok "新增成员，返回一次性临时密码" || bad "新增成员" "$NEW"
DUP=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/users" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"phone\":\"$NEWPHONE\",\"displayName\":\"重复\",\"role\":\"viewer\"}")
[ "$DUP" = "409" ] && ok "同手机号重复加入被拒（409）" || bad "成员唯一" "得到 $DUP"
MYSELF=$(echo "$UL" | python -c "import sys,json;d=json.load(sys.stdin);print(next((x['membershipId'] for x in d['body'] if x['phone'].startswith('138****0001')),''))")
SELFDIS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/users/$MYSELF/disable" -H "Authorization: Bearer $ADMIN")
[ "$SELFDIS" = "409" ] && ok "不能停用自己（409）" || bad "自停用保护" "得到 $SELFDIS"
SELFROLE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/users/$MYSELF" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"role":"viewer"}')
[ "$SELFROLE" = "409" ] && ok "不能降级自己（409）" || bad "自降级保护" "得到 $SELFROLE"
RESET=$(curl -s -X POST "$BASE/api/users/$MID/reset-password" -H "Authorization: Bearer $ADMIN")
RESETPW=$(echo "$RESET" | jget "d['body']['tempPassword']")
[ -n "$RESETPW" ] && ok "重置密码返回新临时密码" || bad "重置密码" "$RESET"
DIS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/users/$MID/disable" -H "Authorization: Bearer $ADMIN")
DST=$(curl -s "$BASE/api/users?keyword=$NEWPHONE" -H "Authorization: Bearer $ADMIN" | jget "d['body'][0]['status']")
[ "$DIS" = "200" ] && [ "$DST" = "disabled" ] && ok "成员停用后状态 disabled" || bad "成员停用" "$DIS/$DST"
curl -s -o /dev/null -X POST "$BASE/api/users/$MID/enable" -H "Authorization: Bearer $ADMIN"
DELM=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/users/$MID" -H "Authorization: Bearer $ADMIN")
LEFTM=$(curl -s "$BASE/api/users?keyword=$NEWPHONE" -H "Authorization: Bearer $ADMIN" | jget "len(d['body'])")
[ "$DELM" = "200" ] && [ "$LEFTM" = "0" ] && ok "成员移出后列表不再返回" || bad "成员移出" "$DELM/$LEFTM"
EXPC=$(curl -s -o target/e2e-users.csv -w '%{http_code}' "$BASE/api/users/export" -H "Authorization: Bearer $ADMIN")
grep -q "手机号,姓名" target/e2e-users.csv && [ "$EXPC" = "200" ] && ok "成员列表可导出 CSV" || bad "成员导出" "得到 $EXPC"

# ---------- 用户体系：图形码 + 短信登录 ----------
# 短信频控是「同号每分钟 1 条、每天 10 条」，e2e 每次重跑都真实发一条，跑到第 11 次必然 429 ——
# 这就是这两条用例长期红灯的根因。修法不是永久 skip，而是运行前清掉该号的发送记录：
#   · 有 MySQL 客户端且库可达 → 直接 delete sms_code（老库/生产巡检环境）；
#   · 否则（例如本机 H2 内存库、无客户端）→ 明确 SKIP 并计入 skipped，绝不计入 PASS。
SMS_PHONE="13800000002"
SMS_READY=1
if db_ready 2>/dev/null; then
  sql_exec "delete from sms_code where phone = '$SMS_PHONE'" >/dev/null 2>&1 \
    && echo "[INFO] 已清理 $SMS_PHONE 的 sms_code 发送记录（解除每日配额）" \
    || { echo "[WARN] sms_code 清理失败（继续跑，可能 429）"; }
else
  SMS_READY=0
  echo "[INFO] 无 MySQL 客户端/库不可达：无法清理 sms_code 配额（H2 内存库每轮重启会重置）"
fi

CAP=$(curl -s "$BASE/api/auth/captcha")
CID=$(echo "$CAP" | jget "d['body']['captchaId']")
CODE=$(echo "$CAP" | python -c "import sys,json,base64,re;d=json.load(sys.stdin);svg=base64.b64decode(d['body']['image'].split(',')[1]).decode();print(''.join(re.findall(r'<text[^>]*>([^<])</text>',svg)))")
[ -n "$CODE" ] && ok "图形验证码下发（识别为 $CODE）" || bad "图形验证码" "$CAP"
SMS=$(curl -s -o target/e2e-sms.json -w '%{http_code}' -X POST "$BASE/api/auth/sms/send" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"13800000002\",\"scene\":\"login\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CODE\"}")
if [ "$SMS" = "200" ]; then ok "短信验证码发送成功（图形码校验通过）"
elif [ "$SMS" = "429" ] && [ "$SMS_READY" = "0" ]; then
  skip "短信验证码发送" "手机号当日 10 条配额已耗尽，且无 MySQL 客户端可清理（需独立测试库）"
else bad "短信发送" "得到 $SMS $(cat target/e2e-sms.json)"; fi
# 生产为随机码；演示环境通过 SMS_EXPOSE_CODE=true 回显，e2e 直接取用
DEMOCODE=$(cat target/e2e-sms.json | jget "d['body'].get('demoCode','')")
LOGINCODE=${DEMOCODE:-123456}
BADSMS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/sms/send" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"13800000002\",\"captchaId\":\"$CID\",\"captchaCode\":\"$CODE\"}")
[ "$BADSMS" = "429" ] || [ "$BADSMS" = "400" ] && ok "图形码一次性 / 短信频控生效（$BADSMS）" || bad "短信防刷" "得到 $BADSMS"
SMSLOGIN=$(curl -s -X POST "$BASE/api/auth/sms/login" -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$SMS_PHONE\",\"code\":\"$LOGINCODE\"}" | jget "d['body']['token']")
if [ -n "$SMSLOGIN" ]; then ok "短信验证码登录拿到 token"
elif [ "$SMS" != "200" ] && [ "$SMS_READY" = "0" ]; then
  skip "短信验证码登录" "上一步未发出验证码（配额耗尽且无法清理）"
else bad "短信登录" "无 token"; fi

CALC=$(curl -s -X POST "$BASE/api/accounting-periods/$PERIOD/calculate" -H "Authorization: Bearer $FILLER")
TOTAL=$(echo "$CALC" | jget "d['body']['total']")
S1=$(echo "$CALC" | jget "d['body']['scope1']")
S2=$(echo "$CALC" | jget "d['body']['scope2']")
VER=$(echo "$CALC" | jget "d['body']['version']")
CV=$(echo "$CALC" | jget "d['body']['calcVersion']")
LINES=$(echo "$CALC" | jget "len(d['body']['lines'])")
python - "$TOTAL" "$S1" "$S2" <<'PY' && ok "核算合计 = scope1 + scope2（$S1 + $S2 = $TOTAL，$LINES 条明细）" || bad "核算合计" "$S1+$S2≠$TOTAL"
import sys
from decimal import Decimal
t,s1,s2=map(Decimal,sys.argv[1:4])
sys.exit(0 if abs(t-(s1+s2))<Decimal('0.0001') else 1)
PY



SUB=$(curl -s -X POST "$BASE/api/accounting-periods/$PERIOD/submit" -H "Authorization: Bearer $FILLER" \
  -H 'Content-Type: application/json' -d "{\"version\":$VER}")
VER2=$(echo "$SUB" | jget "d['body']['version']")
ST=$(echo "$SUB" | jget "d['body']['status']")
[ "$ST" = "submitted" ] && ok "提交成功，状态 submitted" || bad "提交" "状态 $ST"

REP=$(curl -s -X POST "$BASE/api/reports" -H "Authorization: Bearer $FILLER" \
  -H 'Content-Type: application/json' -d "{\"period\":\"$PERIOD\"}")
RID=$(echo "$REP" | jget "d['body']['id']")
track_report "$RID"
SHA=$(echo "$REP" | jget "d['body']['sha256']")
PDF=target/e2e-report.pdf
curl -s "$BASE/api/reports/$RID/download" -H "Authorization: Bearer $FILLER" -o "$PDF"
[ -s "$PDF" ] && ok "报告下载成功（$(stat -c%s "$PDF") 字节）" || bad "报告下载" "空文件"
REALSHA=$(python -c "import hashlib;print(hashlib.sha256(open('$PDF','rb').read()).hexdigest())")
[ "$REALSHA" = "$SHA" ] && ok "文件指纹与生成记录一致" || bad "报告指纹" "$REALSHA ≠ $SHA"
head -c 5 "$PDF" | grep -q '%PDF-' && ok "PDF 文件头有效（正文合计一致性由 mvn test 的 pdfMatchesApiTotal 断言）" || bad "PDF 文件头" "不是有效 PDF"

EARLY=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/accounting-periods/$PERIOD/lock" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d "{\"version\":$VER2}")
[ "$EARLY" = "400" ] && ok "未复核不得锁定（400）" || bad "未复核锁定" "得到 $EARLY"

RV=$(curl -s -X POST "$BASE/api/accounting-periods/$PERIOD/review" -H "Authorization: Bearer $REVIEWER" \
  -H 'Content-Type: application/json' -d "{\"version\":$VER2,\"remark\":\"TST 复核\"}")
VER2=$(echo "$RV" | jget "d['body']['version']")
ST_RV=$(echo "$RV" | jget "d['body']['status']")
[ "$ST_RV" = "reviewed" ] && ok "复核通过，状态 reviewed" || bad "复核" "状态 $ST_RV / $RV"

AP=$(curl -s -X POST "$BASE/api/accounting-periods/$PERIOD/approve" -H "Authorization: Bearer $APPROVER" \
  -H 'Content-Type: application/json' -d "{\"version\":$VER2,\"remark\":\"TST 批准\"}")
VER2=$(echo "$AP" | jget "d['body']['version']")
ST_AP=$(echo "$AP" | jget "d['body']['status']")
[ "$ST_AP" = "approved" ] && ok "批准通过，状态 approved" || bad "批准" "状态 $ST_AP / $AP"

LOCK=$(curl -s -X POST "$BASE/api/accounting-periods/$PERIOD/lock" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"version\":$VER2}")
VER3=$(echo "$LOCK" | jget "d['body']['version']")
ST2=$(echo "$LOCK" | jget "d['body']['status']")
[ "$ST2" = "locked" ] && ok "管理员锁定成功" || bad "锁定" "状态 $ST2"

RID_READ=$(curl -s "$BASE/api/device-readings?deviceId=1" -H "Authorization: Bearer $FILLER" | jget "d['body'][0]['id']")
RV=$(curl -s "$BASE/api/device-readings?deviceId=1" -H "Authorization: Bearer $FILLER" | jget "d['body'][0]['version']")
BLOCK=$(curl -s -o target/e2e-block.json -w '%{http_code}' -X PUT "$BASE/api/device-readings/$RID_READ" \
  -H "Authorization: Bearer $FILLER" -H 'Content-Type: application/json' \
  -d "{\"version\":$RV,\"qty\":1}")
[ "$BLOCK" = "409" ] && ok "锁定后修改读数被拒绝（409）" || bad "锁定拦截" "得到 $BLOCK"

UNLOCK=$(curl -s -X POST "$BASE/api/accounting-periods/$PERIOD/unlock" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"version\":$VER3,\"reason\":\"TST 验证解锁链路\"}")
ST3=$(echo "$UNLOCK" | jget "d['body']['status']")
UNC=$(echo "$UNLOCK" | jget "len(d['body']['unlocks'])")
[ "$ST3" = "draft" ] && [ "$UNC" -ge 1 ] && ok "解锁回 draft，留下 $UNC 条解锁记录" || bad "解锁" "状态 $ST3 / 记录 $UNC"

AUD=$(curl -s "$BASE/api/audit-logs?entity=accounting_period&size=50" -H "Authorization: Bearer $ADMIN")
ACTS=$(echo "$AUD" | jget "sorted({x['action'] for x in d['body']['rows']})")
for a in calculate submit review approve lock unlock; do
  echo "$ACTS" | grep -q "$a" && ok "审计含 $a" || bad "审计缺 $a" "$ACTS"
done

# ---------- P3 产值：创建 → 唯一冲突 → 修改 → 删除 ----------
PV=$(curl -s -X POST "$BASE/api/production-values" -H "Authorization: Bearer $FILLER" \
  -H 'Content-Type: application/json' \
  -d '{"period":"2026-12","productId":1,"value":88.5,"unit":"万元","qty":12,"qtyUnit":"吨"}')
PVID=$(echo "$PV" | jget "d['body']['id']")
TST_PV_IDS="${TST_PV_IDS:+$TST_PV_IDS,}$PVID"
PVNAME=$(echo "$PV" | jget "d['body']['productName']")
[ -n "$PVID" ] && [ "$PVNAME" = "精密轴承座 A 型" ] && ok "产值创建并回填产品名称" || bad "产值创建" "$PV"

PVCODE=$(curl -s -o target/e2e-pv-dup.json -w '%{http_code}' -X POST "$BASE/api/production-values" \
  -H "Authorization: Bearer $FILLER" -H 'Content-Type: application/json' \
  -d '{"period":"2026-12","productId":1,"value":1,"unit":"万元","qty":1,"qtyUnit":"吨"}')
[ "$PVCODE" = "409" ] && ok "同月同产品重复登记被拒绝（409）" || bad "产值唯一性" "得到 $PVCODE"

PVPATCH=$(curl -s -o target/e2e-pv-patch.json -w '%{http_code}' -X PUT "$BASE/api/production-values/$PVID" \
  -H "Authorization: Bearer $FILLER" -H 'Content-Type: application/json' \
  -d '{"period":"2026-12","productId":1,"value":99,"unit":"万元","qty":13,"qtyUnit":"吨","version":0}')
[ "$PVPATCH" = "200" ] && ok "产值修改（乐观锁 v0）" || bad "产值修改" "得到 $PVPATCH"

PVLIST=$(curl -s "$BASE/api/production-values?from=2026-12&to=2026-12" -H "Authorization: Bearer $FILLER")
PVN=$(echo "$PVLIST" | jget "d['body']['total']")
[ "$PVN" -ge 1 ] && ok "产值区间查询 $PVN 条" || bad "产值查询" "只有 $PVN"

PVDEL=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/production-values/$PVID" -H "Authorization: Bearer $ADMIN")
[ "$PVDEL" = "200" ] && ok "产值软删" || bad "产值删除" "得到 $PVDEL"

# ---------- P3 凭证：上传文件 → 绑定 → 解绑 → 删除 ----------
printf '%%PDF-1.4 e2e evidence' > target/e2e-upload.pdf
FID=$(curl -s -X POST "$BASE/api/files" -H "Authorization: Bearer $FILLER" \
  -F "file=@target/e2e-upload.pdf;type=application/pdf" | jget "d['body']['id']")
[ -n "$FID" ] && ok "文件上传拿到 fileId=$FID" || bad "文件上传" "无 fileId"

EVCODE="TST-EV-$TS"
EV=$(curl -s -X POST "$BASE/api/evidences" -H "Authorization: Bearer $FILLER" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"TST 电费发票\",\"code\":\"$EVCODE\",\"validFrom\":\"2026-12-01\",\"validTo\":\"2026-12-31\",\"atDate\":\"2026-12-05\",\"fileIds\":[$FID]}")
EVID=$(echo "$EV" | jget "d['body']['id']")
EVFILES=$(echo "$EV" | jget "len(d['body']['files'])")
[ -n "$EVID" ] && [ "$EVFILES" = "1" ] && ok "凭证创建并绑定 1 个附件" || bad "凭证创建" "$EV"

EVBAD=$(curl -s -o target/e2e-ev-bad.json -w '%{http_code}' -X POST "$BASE/api/evidences" \
  -H "Authorization: Bearer $FILLER" -H 'Content-Type: application/json' \
  -d '{"name":"倒挂","validFrom":"2026-12-31","validTo":"2026-12-01","atDate":"2026-12-05"}')
[ "$EVBAD" = "400" ] && ok "凭证有效期倒挂被拒绝（400）" || bad "凭证有效期" "得到 $EVBAD"

EVCLEAR=$(curl -s -X PUT "$BASE/api/evidences/$EVID" -H "Authorization: Bearer $FILLER" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"TST 电费发票\",\"code\":\"$EVCODE\",\"validFrom\":\"2026-12-01\",\"validTo\":\"2026-12-31\",\"atDate\":\"2026-12-05\",\"fileIds\":[],\"version\":0}")
EVLEFT=$(echo "$EVCLEAR" | jget "len(d['body']['files'])")
[ "$EVLEFT" = "0" ] && ok "凭证解绑附件" || bad "凭证解绑" "$EVCLEAR"

EVDEL=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/evidences/$EVID" -H "Authorization: Bearer $ADMIN")
[ "$EVDEL" = "200" ] && ok "凭证软删" || bad "凭证删除" "得到 $EVDEL"
# 收尾：上传的附件也要删，否则每跑一次 e2e 就多一份测试文件
[ -n "$FID" ] && curl -s -o /dev/null -X DELETE "$BASE/api/files/$FID" -H "Authorization: Bearer $ADMIN"

# ---------- P4 分析：六页 + 首页 ----------
for path in "analysis/energy-consumption?from=2026-09&to=2026-11" \
            "analysis/carbon-emission?from=2026-09&to=2026-11" \
            "analysis/energy-efficiency?from=2026-09&to=2026-11" \
            "analysis/energy-flow?from=2026-09&to=2026-11" \
            "analysis/strategy?from=2026-09&to=2026-11" \
            "analysis/quota?year=2026" \
            "analysis/footprint?year=2026" \
            "analysis/supply-chain"; do
  R=$(curl -s "$BASE/api/$path" -H "Authorization: Bearer $FILLER")
  ST=$(echo "$R" | jget "d['status']")
  KN=$(echo "$R" | jget "len(d['body']['kpis'])")
  [ "$ST" = "0" ] && [ "$KN" -ge 3 ] && ok "分析 ${path%%\?*} 返回 $KN 个 KPI" || bad "分析 ${path%%\?*}" "status=$ST kpis=$KN"
done

OV=$(curl -s "$BASE/api/overview?year=2026" -H "Authorization: Bearer $FILLER")
OVM=$(echo "$OV" | jget "len(d['body']['monthly'])")
OVT=$(echo "$OV" | jget "len(d['body']['todos'])")
OVQ=$(echo "$OV" | jget "d['body']['quota']['total']")
[ "$OVM" = "12" ] && ok "首页聚合：12 个月趋势，待办 $OVT 项，配额 $OVQ" || bad "首页聚合" "月数 $OVM"

BOOT=$(curl -s "$BASE/api/bootstrap?year=2026" -H "Authorization: Bearer $FILLER")
BORG=$(echo "$BOOT" | jget "d['body']['org']['name']")
BPN=$(echo "$BOOT" | jget "len(d['body']['products'])")
BFN=$(echo "$BOOT" | jget "len(d['body']['facilities'])")
[ -n "$BORG" ] && [ "$BPN" -ge 5 ] && [ "$BFN" -ge 2 ] \
  && ok "首页快照：$BORG / $BPN 个产品 / $BFN 个根设施" || bad "首页快照" "$BOOT"

# 前端写操作所需的键：dbId / version / 原始状态
KWC=$(echo "$BOOT" | jget "sum(1 for k in ['products','assets','alertRules','suppliers','accounting','facilities','devices'] if d['body'].get(k) and 'dbId' in d['body'][k][0])")
[ "${KWC:-0}" -ge 5 ] && ok "快照可写实体带 dbId：$KWC 类" || bad "快照 dbId" "$KWC"
RAWST=$(echo "$BOOT" | jget "(d['body'].get('accounting') or [{}])[0].get('rawStatus','')")
[ -n "$RAWST" ] && ok "快照含核算原始状态：$RAWST" || bad "快照核算状态" "$BOOT"
APPCODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/app/index.html")
[ "$APPCODE" = "200" ] && ok "前端入口 /app/index.html 可访问" || bad "前端入口" "$APPCODE"

# ---------- P4 报告多类型：能源 / 足迹 ----------
# 草稿期不允许生成正式报告：先提交本期
P4ST=$(curl -s "$BASE/api/accounting-periods/$PERIOD" -H "Authorization: Bearer $FILLER" | jget "d['body']['status']")
if [ "$P4ST" != "submitted" ] && [ "$P4ST" != "reviewed" ] && [ "$P4ST" != "approved" ] && [ "$P4ST" != "locked" ]; then
  curl -s -o /dev/null -X POST "$BASE/api/accounting-periods/$PERIOD/calculate" -H "Authorization: Bearer $FILLER"
  P4V=$(curl -s "$BASE/api/accounting-periods/$PERIOD" -H "Authorization: Bearer $FILLER" | jget "d['body']['version']")
  curl -s -o /dev/null -X POST "$BASE/api/accounting-periods/$PERIOD/submit" -H "Authorization: Bearer $FILLER" -H 'Content-Type: application/json' -d "{\"version\":$P4V}"
fi
ER=$(curl -s -X POST "$BASE/api/reports" -H "Authorization: Bearer $FILLER" \
  -H 'Content-Type: application/json' -d '{"type":"energy","period":"2026-11"}')
ERID=$(echo "$ER" | jget "d['body']['id']")
track_report "$ERID"
ERSHA=$(echo "$ER" | jget "d['body']['sha256']")
curl -s "$BASE/api/reports/$ERID/download" -H "Authorization: Bearer $FILLER" -o target/e2e-energy.pdf
ERSHA2=$(python -c "import hashlib;print(hashlib.sha256(open('target/e2e-energy.pdf','rb').read()).hexdigest())")
head -c 5 target/e2e-energy.pdf | grep -q '%PDF-' && [ "$ERSHA" = "$ERSHA2" ] \
  && ok "能源报告生成并下载（指纹一致）" || bad "能源报告" "$ER"

FPID=$(curl -s "$BASE/api/footprints?size=5" -H "Authorization: Bearer $FILLER" | jget "d['body']['rows'][0]['id']")
FR=$(curl -s -X POST "$BASE/api/reports" -H "Authorization: Bearer $FILLER" \
  -H 'Content-Type: application/json' -d "{\"type\":\"footprint\",\"footprintId\":$FPID}")
FRID=$(echo "$FR" | jget "d['body']['id']")
track_report "$FRID"
curl -s "$BASE/api/reports/$FRID/download" -H "Authorization: Bearer $FILLER" -o target/e2e-footprint.pdf
head -c 5 target/e2e-footprint.pdf | grep -q '%PDF-' && ok "足迹报告生成并下载" || bad "足迹报告" "$FR"

RLIST=$(curl -s "$BASE/api/reports?type=energy&period=2026-11" -H "Authorization: Bearer $FILLER" | jget "len(d['body']['rows'])")
[ "$RLIST" -ge 1 ] && ok "报告按类型/期间查询 $RLIST 条" || bad "报告查询" "0 条"

# ---------- 设施树 ----------
FAC=$(curl -s -X POST "$BASE/api/facilities" -H "Authorization: Bearer $FILLER" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"TST 车间\",\"code\":\"TST-F-$TS\",\"parentId\":1,\"sort\":9}")
FACID=$(echo "$FAC" | jget "d['body']['id']")
FACLV=$(echo "$FAC" | jget "d['body']['level']")
[ -n "$FACID" ] && [ "$FACLV" = "2" ] && ok "设施创建：父级 1 级 → 子级 2 级" || bad "设施创建" "$FAC"

FACN=$(curl -s "$BASE/api/facilities?size=50" -H "Authorization: Bearer $FILLER" | jget "d['body']['total']")
[ "$FACN" -ge 6 ] && ok "设施列表 $FACN 条" || bad "设施列表" "只有 $FACN"

FACDEL=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/facilities/$FACID" -H "Authorization: Bearer $ADMIN")
[ "$FACDEL" = "200" ] && ok "设施删除（叶子节点）" || bad "设施删除" "得到 $FACDEL"

# ---------- 导出与报告打包 ----------
CSVCODE=$(curl -s -o target/e2e-export.csv -w '%{http_code}' \
  "$BASE/api/export?resource=production-values&from=2026-09&to=2026-11" -H "Authorization: Bearer $FILLER")
python - <<'PY' && ok "产值 CSV 导出（BOM + 表头 + 数据）" || bad "CSV 导出" "内容不符合预期"
import sys
d=open('target/e2e-export.csv','rb').read()
assert d.startswith(b'\xef\xbb\xbf'), 'missing BOM'
assert '月份,产品,产值'.encode() in d
assert '精密轴承座 A 型'.encode() in d
PY

CSVBAD=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/export?resource=nope" -H "Authorization: Bearer $FILLER")
[ "$CSVBAD" = "400" ] && ok "未知导出资源被拒绝（400）" || bad "导出校验" "得到 $CSVBAD"

ZIPCODE=$(curl -s -o target/e2e-reports.zip -w '%{http_code}' \
  "$BASE/api/reports/bundle?period=2026-11" -H "Authorization: Bearer $FILLER")
head -c 2 target/e2e-reports.zip | grep -q 'PK' && [ "$ZIPCODE" = "200" ] \
  && ok "报告 ZIP 打包（$(stat -c%s target/e2e-reports.zip) 字节）" || bad "报告打包" "得到 $ZIPCODE"

# ---------- 登录失败锁定 ----------
# 密码错误要计入失败次数，每次必须配一张新的正确验证码，
# 否则会被验证码拦下而永远触发不了锁定
for i in 1 2 3 4 5; do
  LC=$(cap_token); LID=${LC%%|*}; LCODE=${LC##*|}
  curl -s -o /dev/null -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"e2e-nobody\",\"password\":\"bad\",\"captchaId\":\"$LID\",\"captchaCode\":\"$LCODE\"}"
done
LC=$(cap_token); LID=${LC%%|*}; LCODE=${LC##*|}
LOCKCODE=$(curl -s -o target/e2e-lock.json -w '%{http_code}' -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"e2e-nobody\",\"password\":\"bad\",\"captchaId\":\"$LID\",\"captchaCode\":\"$LCODE\"}")
[ "$LOCKCODE" = "429" ] && ok "连续 5 次失败后锁定（429）" || bad "登录锁定" "得到 $LOCKCODE"

# ---------- 设备台账 CRUD ----------
TS=$(date +%H%M%S)
DCODE="EQ-TST-$TS"
DID=$(curl -s -X POST "$BASE/api/devices" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"TST 设备\",\"code\":\"$DCODE\",\"energyType\":\"外购电力\",\"status\":\"online\",\"deviceType\":\"电表\",\"level\":\"主要用能设备\"}" \
  | jget "d['body']['id']")
[ -n "$DID" ] && ok "设备新建：id=$DID" || bad "设备新建" "无 id"
DUPCODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/devices" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"name\":\"重复\",\"code\":\"$DCODE\",\"energyType\":\"外购电力\"}")
[ "$DUPCODE" = "409" ] && ok "设备编码重复被拒（409）" || bad "设备编码唯一" "得到 $DUPCODE"
PV=$(curl -s "$BASE/api/devices?keyword=$DCODE" -H "Authorization: Bearer $ADMIN" | jget "d['body'][0]['version']")
PCODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/devices/$DID" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"name\":\"TST 设备-改\",\"code\":\"$DCODE\",\"energyType\":\"外购电力\",\"version\":$PV}")
[ "$PCODE" = "200" ] && ok "设备修改 v$PV → v$((PV+1))" || bad "设备修改" "得到 $PCODE"
DIS=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/devices/$DID/disable" -H "Authorization: Bearer $ADMIN")
LEFT=$(curl -s "$BASE/api/devices?keyword=$DCODE" -H "Authorization: Bearer $ADMIN" | jget "len(d['body'])")
[ "$DIS" = "200" ] && [ "$LEFT" = "0" ] && ok "设备禁用后列表不返回" || bad "设备禁用" "got $DIS/$LEFT"
curl -s -o /dev/null -X POST "$BASE/api/devices/$DID/enable" -H "Authorization: Bearer $ADMIN"
DELD=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/devices/$DID" -H "Authorization: Bearer $ADMIN")
[ "$DELD" = "200" ] && ok "无读数设备可删除" || bad "设备删除" "得到 $DELD"

# ---------- 配额年度 CRUD ----------
QY=$((2030 + (10#$TS % 5)))
QID=$(curl -s -X POST "$BASE/api/quota-years" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d "{\"year\":$QY,\"total\":12000,\"next\":11000,\"note\":\"TST\"}" | jget "d['body']['id']")
TST_QID="${QID:-}"
[ -n "$QID" ] && ok "配额年度新建：$QY 年" || bad "配额年度新建" "无 id"
QCODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/quota-years/$QID" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d "{\"year\":$QY,\"total\":13000,\"next\":12000,\"version\":0}")
[ "$QCODE" = "200" ] && ok "配额年度修改 v0 → v1" || bad "配额年度修改" "得到 $QCODE"
QDEL=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/quota-years/$QID" -H "Authorization: Bearer $ADMIN")
[ "$QDEL" = "200" ] && ok "配额年度删除" || bad "配额年度删除" "得到 $QDEL"

# ---------- 台账批量导入 ----------
IMPS=$(printf '\xEF\xBB\xBF编码,名称,规格型号,产品类型,产品分类,单位\nTST-P-%s,导入产品,TS,一般产品,工业产品,吨\n' "$TS" > target/e2e-products.csv; wc -c < target/e2e-products.csv)
IMP1=$(curl -s -X POST "$BASE/api/import?resource=products" -H "Authorization: Bearer $FILLER" -F "file=@target/e2e-products.csv")
IMPOK=$(echo "$IMP1" | jget "d['body']['success']")
[ "$IMPOK" = "1" ] && ok "产品 CSV 导入成功 1 条" || bad "产品导入" "$IMP1"
IMP2=$(curl -s -X POST "$BASE/api/import?resource=products" -H "Authorization: Bearer $FILLER" -F "file=@target/e2e-products.csv")
IMPBAD=$(echo "$IMP2" | jget "d['body']['failed']")
[ "$IMPBAD" = "1" ] && ok "重复导入该行失败并返回原因" || bad "重复导入" "$IMP2"
IMPPID=$(curl -s "$BASE/api/products?keyword=TST-P-$TS" -H "Authorization: Bearer $ADMIN" | jget "d['body']['rows'][0]['id']")
[ -n "$IMPPID" ] && curl -s -o /dev/null -X DELETE "$BASE/api/products/$IMPPID" -H "Authorization: Bearer $ADMIN"
TPLCODE=$(curl -s -o target/e2e-tpl.csv -w '%{http_code}' "$BASE/api/import/template?resource=products" -H "Authorization: Bearer $FILLER")
grep -q "编码,名称" target/e2e-tpl.csv && [ "$TPLCODE" = "200" ] && ok "导入模板可下载" || bad "导入模板" "得到 $TPLCODE"

# ---------- 定时任务与健康检查 ----------
JOBCODE=$(curl -s -o target/e2e-job.json -w '%{http_code}' -X POST "$BASE/api/jobs/scan-alerts/run" -H "Authorization: Bearer $ADMIN")
JOBNAME=$(cat target/e2e-job.json | jget "d['body']['job']")
[ "$JOBCODE" = "200" ] && [ "$JOBNAME" = "scan-alerts" ] && ok "预警扫描任务可手动触发" || bad "定时任务" "得到 $JOBCODE/$JOBNAME"
JOBF=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/jobs/scan-alerts/run" -H "Authorization: Bearer $FILLER")
[ "$JOBF" = "403" ] && ok "填报员不能触发任务（403）" || bad "任务权限" "得到 $JOBF"
HLTH=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/health")
[ "$HLTH" = "200" ] && ok "健康检查免登录（200）" || bad "健康检查" "得到 $HLTH"

# ---------- 平台运营端 ----------
PLAT=$(login 13900000000 'Superadmin@2026!Pass')
[ -n "$PLAT" ] && ok "平台运营账号可登录" || bad "平台账号登录" "无 token"
PTN=$(curl -s "$BASE/api/platform/tenants" -H "Authorization: Bearer $PLAT" | jget "d['body']['total']")
[ "$PTN" -ge 1 ] && ok "平台可列出 $PTN 个租户" || bad "租户列表" "只有 $PTN"
PFORB=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/platform/tenants" -H "Authorization: Bearer $ADMIN")
[ "$PFORB" = "403" ] && ok "普通管理员不能进平台端（403）" || bad "平台权限" "得到 $PFORB"
PTS="$TS"
TPHONE="1390000$(printf '%04d' $((10#$PTS % 10000)))"
track_phone "$TPHONE"
NEWT=$(curl -s -X POST "$BASE/api/platform/tenants" -H "Authorization: Bearer $PLAT" -H 'Content-Type: application/json' \
  -d "{\"name\":\"TST 租户 $PTS\",\"code\":\"TST-T-$PTS\",\"adminPhone\":\"$TPHONE\",\"adminName\":\"租户管理员\",\"plan\":\"basic\",\"seatLimit\":5}")
TID=$(echo "$NEWT" | jget "d['body']['orgId']")
TTMP=$(echo "$NEWT" | jget "d['body']['tempPassword']")
[ -n "$TID" ] && [ -n "$TTMP" ] && ok "平台开通租户 orgId=$TID，返回初始密码" || bad "开通租户" "$NEWT"
TLOGIN=$(login "$TPHONE" "$TTMP")
[ -n "$TLOGIN" ] && ok "新租户管理员可登录" || bad "新租户登录" "无 token"
SUSP=$(curl -s -X PUT "$BASE/api/platform/tenants/$TID" -H "Authorization: Bearer $PLAT" \
  -H 'Content-Type: application/json' -d '{"status":"suspended"}' | jget "d['body']['status']")
[ "$SUSP" = "suspended" ] && ok "平台可停用租户" || bad "停用租户" "得到 $SUSP"
SUSPBLK=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/overview" -H "Authorization: Bearer $TLOGIN")
[ "$SUSPBLK" = "423" ] && ok "租户停用后成员请求被拒（423）" || bad "停用拦截" "得到 $SUSPBLK"

# ---------- P3 平台概览 / 审计 / 到期提醒 ----------
OV=$(curl -s "$BASE/api/platform/overview" -H "Authorization: Bearer $PLAT")
OVT=$(echo "$OV" | jget "d['body']['stats']['tenants']")
OVR=$(echo "$OV" | jget "len(d['body']['recent'])")
[ "${OVT:-0}" -ge 1 ] && ok "平台概览返回租户总数 $OVT" || bad "平台概览" "$OV"
[ "${OVR:-0}" -ge 1 ] && ok "平台概览含最近平台操作 $OVR 条" || bad "概览最近操作" "$OVR"

PA=$(curl -s "$BASE/api/platform/audit?size=5&action=create" -H "Authorization: Bearer $PLAT")
PAT=$(echo "$PA" | jget "d['body']['total']")
PAL=$(echo "$PA" | jget "d['body']['rows'][0]['actionLabel']")
[ "${PAT:-0}" -ge 1 ] && [ "$PAL" = "开通" ] && ok "平台审计按动作筛选（开通 $PAT 条）" || bad "平台审计" "$PA"

AU=$(curl -s "$BASE/api/audit-logs?page=1&size=5" -H "Authorization: Bearer $ADMIN")
AUT=$(echo "$AU" | jget "d['body']['total']")
AUS=$(echo "$AU" | jget "len(d['body']['rows'])")
[ "${AUT:-0}" -ge 1 ] && [ "${AUS:-0}" -le 5 ] && ok "租户审计分页 total=$AUT 首页 $AUS 条" || bad "租户审计" "$AU"
AUF=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/audit-logs" -H "Authorization: Bearer $FILLER")
[ "$AUF" = "403" ] && ok "普通用户访问审计 403" || bad "审计权限" "得到 $AUF"
LIC=$(curl -s "$BASE/api/auth/me" -H "Authorization: Bearer $ADMIN" | jget "d['body']['user']['licenseStatus']")
[ "$LIC" = "active" ] && ok "me 下发授权状态 active" || bad "授权期下发" "得到 $LIC"

# ---------- 集团汇总与换绑 ----------
GORG=$(curl -s "$BASE/api/group/summary" -H "Authorization: Bearer $ADMIN" | jget "d['body']['orgCount']")
[ "$GORG" -ge 1 ] && ok "集团汇总返回 $GORG 家企业" || bad "集团汇总" "得到 $GORG"
RB=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/phone/rebind" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"oldCode":"000000","newPhone":"13999998888","newCode":"000000"}')
[ "$RB" = "400" ] && ok "换绑手机号需双验证码（缺码 400）" || bad "换绑保护" "得到 $RB"

# ---------- 企业信息 ----------
ORG=$(curl -s "$BASE/api/org" -H "Authorization: Bearer $FILLER")
ORGS=$(echo "$ORG" | jget "d['body']['shortName']")
[ "$ORGS" = "华东精密" ] && ok "企业信息返回简称：$ORGS" || bad "企业信息" "$ORG"

# ---------- 测试数据清理与残留回查 ----------
cleanup_test_data
# 注意：MYSQL_BIN_PATH 非空只说明"装了客户端"，不代表库连得上；必须用 db_ready 判连通性，
# 否则连接失败时 LEFT 为空，会误报"残留未清理"（历史 1 条假红灯就是这么来的）。
if db_ready 2>/dev/null; then
  LEFT=$(sql_scalar "select
      (select count(*) from product   where code like '%TST%' and code like '%$TS%')
    + (select count(*) from evidence  where code like '%TST%' and code like '%$TS%')
    + (select count(*) from device    where code like '%TST%' and code like '%$TS%')
    + (select count(*) from facility  where code like '%TST%' and code like '%$TS%')
    + (select count(*) from org       where code like '%TST%' and code like '%$TS%')
    + (select count(*) from production_value where id in (${TST_PV_IDS:-0}))")
  [ "${LEFT:-1}" = "0" ] && ok "测试数据残留 0（标记 TST-$TS）" || bad "测试数据残留" "${LEFT} 条未清理"
else
  skip "测试数据残留回查" "无 MySQL 客户端或库不可达（H2 内存库无客户端），未执行残留统计"
fi

echo
echo "RESULT: $([ $FAIL -eq 0 ] && echo PASS || echo FAIL) ($PASS passed, $FAIL failed, $SKIP skipped)"
[ $FAIL -eq 0 ] || exit 1
