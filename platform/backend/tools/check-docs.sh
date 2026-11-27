#!/usr/bin/env bash
# 文档一致性门禁：活文档里不允许出现过期数字/旧口径，且关键统计必须与源码事实一致。
# 用法：bash platform/backend/tools/check-docs.sh
#
# 两段检查：
#   [1] 旧口径黑名单（历史遗留写法）
#   [2] 事实断言：登记表「文件 | 正则 | 期望来源」，逐条与 lib-facts.sh 的实时值比对
#
# 说明：
#   - 历史批次报告（platform/docs/report-*.md）与复盘允许保留当时数字，不在扫描范围；
#   - 早期版本本脚本以 platform/backend 为基准目录，登记的 README.md/docs/* 实际都不存在，
#     导致"0 处过期口径"是假绿（P2-3）。现在统一以仓库根为基准，并在文件缺失时直接 FAIL。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT" || exit 1

# shellcheck source=platform/tools/lib-facts.sh
. "$ROOT/platform/tools/lib-facts.sh"

FILES=(
  platform/README.md
  platform/docs/architecture.md
  platform/docs/deploy.md
  platform/docs/release-and-rollback.md
  platform/docs/error-codes.md
  platform/docs/performance-budget.md
  platform/docs/state-machines.md
  platform/docs/permissions.md
  platform/docs/traceability-matrix.md
  platform/docs/user-manual.md
  platform/docs/collaboration.md
  platform/docs/user-system-redesign.md
  platform/docs/user-system-api.md
  platform/docs/problem-library.md
  platform/docs/templates/README.md
  platform/docs/retrospectives/INDEX.md
  platform/docs/retrospectives/actions.md
  HANDOVER.md
  carbonbal-web/AGENTS.md
  carbonbal-web/docs/字段与口径.md
  carbonbal-web/docs/requirements/INDEX.md
)

HITS=0
MISSING=0

# 登记的文件必须真实存在：缺文件 = 漏检，直接判失败（不允许静默跳过）
for f in "${FILES[@]}"; do
  if [ ! -f "$f" ]; then
    echo "[FAIL] 登记文档不存在（会形成假绿）：$f"
    MISSING=$((MISSING+1))
    HITS=$((HITS+1))
  fi
done

# ---------- [1] 过期数字 / 旧口径（左列是人话，右列是正则） ----------
PATTERNS=(
  '旧接口数 152|152 个 API'
  '旧接口数 154|154 个 API'
  '旧断言数 58|58 条断言'
  '旧端到端 100|100 项端到端'
  '旧表数 34|34 张业务表'
  '旧物理表 35|物理 35'
  '旧发布验证 10 项|10 项验证'
  '旧 UI 门禁 6 项|check-ui[^。]*6 项'
  '旧路由 33|33/33 路由'
  'mock 数据源|assets/data\.js'
  '原型演示文案|原型演示模式'
  '固定验证码|固定码 123456'
  '硬编码 Flyway|flyway=105'
)

for item in "${PATTERNS[@]}"; do
  label="${item%%|*}"
  re="${item#*|}"
  for f in "${FILES[@]}"; do
    [ -f "$f" ] || continue
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      echo "[FAIL] $label → $f:$line"
      HITS=$((HITS+1))
    done < <(grep -nE "$re" "$f" 2>/dev/null | head -5)
  done
done

# ---------- [2] 事实断言：文档数字必须等于源码事实 ----------
# 每个匹配都必须等于期望值（按数字出现顺序比对），一个都不许漏。
fact_assert() {
  local file="$1" re="$2" label="$3"
  shift 3
  local -a expected=("$@")
  local found=0
  if [ ! -f "$file" ]; then
    echo "[FAIL] 事实断言($label)：缺少文件 $file"
    HITS=$((HITS+1))
    return
  fi
  while IFS= read -r match; do
    [ -z "$match" ] && continue
    found=$((found+1))
    local -a actual=()
    local n
    while IFS= read -r n; do
      [ -n "$n" ] && actual+=("$n")
    done < <(printf '%s' "$match" | grep -oE '[0-9]+')
    local ok=1 i
    if [ "${#actual[@]}" -ne "${#expected[@]}" ]; then ok=0; fi
    if [ "$ok" -eq 1 ]; then
      for ((i=0; i<${#expected[@]}; i++)); do
        if [ "${actual[$i]:-}" != "${expected[$i]}" ]; then ok=0; fi
      done
    fi
    if [ "$ok" -eq 0 ]; then
      echo "[FAIL] 事实断言($label)：$file 写着「$match」，源码事实为 ${expected[*]}"
      HITS=$((HITS+1))
    fi
  done < <(grep -oE "$re" "$file" 2>/dev/null)
  if [ "$found" -eq 0 ]; then
    echo "[FAIL] 事实断言($label)：$file 未找到可校验的统计口径（正则 $re）"
    HITS=$((HITS+1))
  fi
}

# Flyway 版本 / 迁移个数 / 测试用例数：全部来自 lib-facts.sh
fact_assert "HANDOVER.md" 'Flyway [0-9]+' "HANDOVER Flyway 版本" "$FACTS_FLYWAY_VERSION"
fact_assert "HANDOVER.md" 'V[0-9]+–V[0-9]+' "HANDOVER 迁移区间" "1" "$FACTS_FLYWAY_VERSION"
fact_assert "HANDOVER.md" '[0-9]+ 个迁移' "HANDOVER 迁移个数" "$FACTS_MIGRATIONS"
fact_assert "HANDOVER.md" '[0-9]+ 条断言' "HANDOVER 断言个数" "$FACTS_TEST_CASES"
fact_assert "HANDOVER.md" 'mvn test` \| [0-9]+/[0-9]+' "HANDOVER 验收表" "$FACTS_TEST_CASES" "$FACTS_TEST_CASES"

fact_assert "platform/README.md" '[0-9]+ 条断言' "platform/README 断言个数" "$FACTS_TEST_CASES"

fact_assert "platform/docs/release-and-rollback.md" 'Flyway 版本 \| [0-9]+（[0-9]+ 个迁移）' \
  "发布文档 Flyway" "$FACTS_FLYWAY_VERSION" "$FACTS_MIGRATIONS"
fact_assert "platform/docs/release-and-rollback.md" '\| [0-9]+/[0-9]+，' \
  "发布文档门禁" "$FACTS_TEST_CASES" "$FACTS_TEST_CASES"

fact_assert "platform/docs/architecture.md" '[0-9]+ 条断言' "架构文档断言个数" "$FACTS_TEST_CASES"

echo
echo "事实源：version=$FACTS_VERSION flyway=$FACTS_FLYWAY_VERSION migrations=$FACTS_MIGRATIONS test_classes=$FACTS_TEST_CLASSES test_cases=$FACTS_TEST_CASES java_main=$FACTS_JAVA_MAIN"
if [ "$HITS" -eq 0 ]; then
  echo "RESULT: PASS（${#FILES[@]} 份活文档，0 处过期口径，事实断言全部一致）"
else
  echo "RESULT: FAIL（$HITS 处不一致；登记文档缺失 $MISSING 份）"
fi
[ "$HITS" -eq 0 ]
