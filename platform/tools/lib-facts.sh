#!/usr/bin/env bash
# 交付数字的【唯一事实源】：全部从源码实时计算，禁止在任何脚本/文档里再硬编码。
#
# 用法：
#   source platform/tools/lib-facts.sh
#   echo "$FACTS_VERSION $FACTS_FLYWAY_VERSION/$FACTS_MIGRATIONS $FACTS_TEST_CASES"
#
# 提供变量：
#   FACTS_ROOT            仓库根（D:/能碳12 等价路径）
#   FACTS_VERSION         应用版本（carbonbal-web/VERSION）
#   FACTS_FLYWAY_VERSION  Flyway 最大迁移编号（V*.sql 的数字最大值）
#   FACTS_MIGRATIONS      迁移文件个数
#   FACTS_TEST_CLASSES    *Test.java 个数
#   FACTS_TEST_CASES      @Test 方法个数（\b 排除 @TestInstance/@TestMethodOrder）
#   FACTS_JAVA_MAIN       src/main/java 下 .java 文件数
#
# 说明：本文件只做计算与导出，不修改调用方的 shell 选项（可安全被 set -e 脚本 source）。

if [ -n "${BASH_SOURCE[0]:-}" ]; then
  _FACTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
else
  _FACTS_DIR="$(pwd)"
fi

FACTS_ROOT="$(cd "$_FACTS_DIR/../.." && pwd)"
FACTS_FE="$FACTS_ROOT/carbonbal-web"
FACTS_BE="$FACTS_ROOT/platform/backend"
FACTS_MIGRATIONS_DIR="$FACTS_BE/src/main/resources/db/migration"

FACTS_VERSION="$(tr -d '[:space:]' < "$FACTS_FE/VERSION" 2>/dev/null)"
FACTS_MIGRATIONS="$(find "$FACTS_MIGRATIONS_DIR" -maxdepth 1 -type f -name 'V*__*.sql' 2>/dev/null | wc -l | tr -d ' ')"
FACTS_FLYWAY_VERSION="$(find "$FACTS_MIGRATIONS_DIR" -maxdepth 1 -type f -name 'V*__*.sql' 2>/dev/null \
  | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tail -1)"
FACTS_TEST_CLASSES="$(find "$FACTS_BE/src/test/java" -type f -name '*Test.java' 2>/dev/null | wc -l | tr -d ' ')"
FACTS_TEST_CASES="$(grep -rhE '^[[:space:]]*@Test\b' --include='*.java' "$FACTS_BE/src/test/java" 2>/dev/null | wc -l | tr -d ' ')"
FACTS_JAVA_MAIN="$(find "$FACTS_BE/src/main/java" -type f -name '*.java' 2>/dev/null | wc -l | tr -d ' ')"

# 缺值即视为环境损坏：宁可炸掉，也不要让文档/清单写进空数字
if [ -z "$FACTS_VERSION" ] || [ -z "$FACTS_FLYWAY_VERSION" ] || [ -z "$FACTS_MIGRATIONS" ]; then
  echo "[FATAL] lib-facts 无法从源码计算事实（ROOT=$FACTS_ROOT）：请确认在完整源码树内执行" >&2
  return 1 2>/dev/null || exit 1
fi

export FACTS_ROOT FACTS_VERSION FACTS_FLYWAY_VERSION FACTS_MIGRATIONS
export FACTS_TEST_CLASSES FACTS_TEST_CASES FACTS_JAVA_MAIN

# 便于人工核对：直接执行本脚本时打印一行摘要
if [ "${BASH_SOURCE[0]:-}" = "$0" ]; then
  echo "version=$FACTS_VERSION flyway=$FACTS_FLYWAY_VERSION migrations=$FACTS_MIGRATIONS test_classes=$FACTS_TEST_CLASSES test_cases=$FACTS_TEST_CASES java_main=$FACTS_JAVA_MAIN"
fi
