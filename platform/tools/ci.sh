#!/usr/bin/env bash
# 一键门禁（P1-9 / P2-8）：把"能机器判定"的检查串成一条命令，退出码即结论。
#
# 用法：
#   bash platform/tools/ci.sh                  # 离线必备段：事实源 → 文档 → 前端同源 → 单测 → L0 → XSS → 打包自检
#   bash platform/tools/ci.sh --with-service   # 追加需要活服务的段：smoke → e2e → ui-flows → check-ui
#
# 结论语义（禁止假绿）：
#   PASS     所有段通过，无跳过
#   PARTIAL  没有失败，但存在 [SKIP]（服务未起 / 无 MySQL 客户端 / 需联网的 SAST）
#   FAIL     任一段失败，退出码 1
#
# 每段都会打印自己的输出；汇总表在最后。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1

WITH_SERVICE=0
for a in "$@"; do
  case "$a" in
    --with-service) WITH_SERVICE=1 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "[FATAL] 未知参数：$a" >&2; exit 2 ;;
  esac
done

# shellcheck source=platform/env.sh
[ -f "$ROOT/platform/env.sh" ] && . "$ROOT/platform/env.sh"

BASE="${BASE:-http://127.0.0.1:8088}"
export BASE

ENTITY=()          # "name|status|detail"
FAILS=0
SKIPS=0

record() { ENTITY+=("$1|$2|$3"); }
stage() { echo; echo "===== [$1] $2 ====="; }

service_up() {
  curl -sf -m 3 "$BASE/api/health" 2>/dev/null | grep -q '"status":"UP"'
}

# ---------- 1. 事实源可计算（P0-3 单一来源） ----------
stage 1/8 "事实源 lib-facts"
if OUT=$(bash platform/tools/lib-facts.sh 2>&1); then
  echo "$OUT"
  record "事实源" PASS "$OUT"
else
  echo "$OUT"
  record "事实源" FAIL "lib-facts 无法从源码计算"
  FAILS=$((FAILS+1))
fi

# ---------- 2. 文档数字与源码同源（P2-3） ----------
stage 2/8 "文档断言 check-docs"
if OUT=$(bash platform/backend/tools/check-docs.sh 2>&1); then
  echo "$OUT" | tail -5
  record "文档断言" PASS "$(echo "$OUT" | tail -1)"
else
  echo "$OUT" | tail -20
  record "文档断言" FAIL "见上方输出"
  FAILS=$((FAILS+1))
fi

# ---------- 3. 前端源码与产物同源 ----------
stage 3/8 "前端同步 sync-frontend --check"
if OUT=$(bash platform/tools/sync-frontend.sh --check 2>&1); then
  echo "$OUT"
  record "前端同源" PASS "$OUT"
else
  echo "$OUT"
  record "前端同源" FAIL "产物已漂移，请执行 bash platform/tools/sync-frontend.sh"
  FAILS=$((FAILS+1))
fi

# ---------- 4. 后端单测 ----------
stage 4/8 "mvn -o test"
MVN_LOG="$(mktemp)"
if (cd platform/backend && mvn -o test > "$MVN_LOG" 2>&1); then
  LINE=$(grep -aE "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$MVN_LOG" | tail -1)
  echo "$LINE"
  record "后端单测" PASS "$LINE"
else
  grep -aE "Tests run:|<<< (FAILURE|ERROR)|ERROR\]   " "$MVN_LOG" | tail -25
  record "后端单测" FAIL "见平台后端 target/surefire-reports"
  FAILS=$((FAILS+1))
fi
rm -f "$MVN_LOG"

# ---------- 5. 前端 L0 静态门禁 ----------
stage 5/8 "前端 gate-l0"
if OUT=$(cd 自研原型 && node tools/gate-l0.cjs 2>&1); then
  echo "$OUT" | tail -3
  record "前端 L0" PASS "$(echo "$OUT" | grep '^RESULT' | tail -1)"
else
  echo "$OUT" | tail -20
  record "前端 L0" FAIL "见上方输出"
  FAILS=$((FAILS+1))
fi

# ---------- 6. 前端 XSS 静态断言（P1-5 回归护栏） ----------
stage 6/8 "前端 XSS 断言"
if OUT=$(cd 自研原型 && node tools/xss-check.cjs 2>&1); then
  echo "$OUT" | tail -2
  record "XSS 断言" PASS "$(echo "$OUT" | grep '^RESULT' | tail -1)"
else
  echo "$OUT" | tail -10
  record "XSS 断言" FAIL "见上方输出"
  FAILS=$((FAILS+1))
fi

# ---------- 7. 交付包自检 ----------
stage 7/8 "交付包自检 package-source --check"
if OUT=$(bash platform/tools/package-source.sh --check 2>&1); then
  echo "$OUT" | tail -5
  record "交付包自检" PASS "$(echo "$OUT" | tail -1)"
else
  echo "$OUT" | tail -15
  record "交付包自检" FAIL "见上方输出"
  FAILS=$((FAILS+1))
fi

# ---------- 8. 活服务段（可选） ----------
stage 8/8 "活服务段（--with-service）"
if [ "$WITH_SERVICE" = "0" ]; then
  echo "[SKIP] 未指定 --with-service：跳过 smoke / e2e / ui-flows / check-ui"
  record "活服务段" SKIP "未指定 --with-service"
  SKIPS=$((SKIPS+1))
elif ! service_up; then
  echo "[SKIP] $BASE 不可达：跳过 smoke / e2e / ui-flows / check-ui"
  record "活服务段" SKIP "服务不可达 $BASE"
  SKIPS=$((SKIPS+1))
else
  for spec in "smoke:platform/backend/tools/smoke.sh:bash" \
              "e2e:platform/backend/tools/e2e.sh:bash" \
              "ui-flows:tools/ui-flows.cjs:node" \
              "check-ui:tools/check-ui.cjs:node"; do
    NAME=${spec%%:*}; REST=${spec#*:}; FILE=${REST%%:*}; RUNNER=${REST##*:}
    if [ "$RUNNER" = "node" ]; then CMD="node $FILE"; DIR="自研原型"; else CMD="bash $FILE"; DIR="."; fi
    echo; echo "--- $NAME ---"
    OUT=$(cd "$DIR" && eval "$CMD" 2>&1); RC=$?
    if [ "$RC" = "0" ]; then
      echo "$OUT" | grep -E '^\[(FAIL|SKIP)\]|^RESULT' | tail -6
      record "$NAME" PASS "$(echo "$OUT" | grep '^RESULT' | tail -1)"
    else
      echo "$OUT" | grep -E '^\[FAIL\]|^RESULT' | tail -10
      # 退出码 2 = 环境缺失（如浏览器/Playwright 未安装）→ 记为 SKIP 而非 FAIL
      if [ "$RC" = "2" ]; then
        record "$NAME" SKIP "环境缺失（退出码 2）"
        SKIPS=$((SKIPS+1))
      else
        record "$NAME" FAIL "见上方输出"
        FAILS=$((FAILS+1))
      fi
    fi
  done
fi

# ---------- 需要联网的工具链：显式占位 ----------
echo
echo "[SKIP] SAST/SCA（Semgrep / OWASP dependency-check / npm audit）需联网与工具链授权，本机未执行"
echo "       可单独运行：bash platform/tools/deps-audit.sh（需要网络）"
record "SAST/SCA" SKIP "需联网，未执行（脚本骨架见 platform/tools/deps-audit.sh）"
SKIPS=$((SKIPS+1))

# ---------- 汇总 ----------
echo
echo "================ 汇总 ================"
for e in "${ENTITY[@]}"; do
  IFS='|' read -r n s d <<< "$e"
  printf '[%-4s] %-14s %s\n' "$s" "$n" "$d"
done
echo "======================================"
if [ "$FAILS" -gt 0 ]; then
  echo "RESULT: FAIL ($FAILS 段失败, $SKIPS 段跳过)"
  exit 1
elif [ "$SKIPS" -gt 0 ]; then
  echo "RESULT: PARTIAL (0 段失败, $SKIPS 段跳过 —— 跳过不计入通过)"
  exit 0
else
  echo "RESULT: PASS (全部段通过)"
  exit 0
fi
