#!/usr/bin/env bash
# 源码交付包：可复现地打出自包含源码 zip / tar.gz
# 用法：
#   bash platform/tools/package-source.sh [输出目录，默认 <repo>/dist]   # 打包
#   bash platform/tools/package-source.sh --check                        # 只自检，不落盘（CI 用）
#
# 同源保证（P0-3）：MANIFEST 里的每个数字都由 lib-facts.sh 从源码实时计算，
# 并且在打包后对"暂存区"再算一遍做交叉校验，任何不一致立即 exit 1。
# 绝不再出现"包里的 MANIFEST 写着 flyway=105、源码其实已到 V115"这类不同源问题。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=platform/tools/lib-facts.sh
. "$ROOT/platform/tools/lib-facts.sh"

# --check：交付物必需的文件与事实源是否齐备（不产包，供 ci.sh 调用）
CHECK_ONLY=0
DIST_ARG=""
for a in "$@"; do
  case "$a" in
    --check) CHECK_ONLY=1 ;;
    *) DIST_ARG="$a" ;;
  esac
done

VER="$FACTS_VERSION"
NAME="carbonbal-$VER"
DIST="${DIST_ARG:-$ROOT/dist}"
STAGE_ROOT="$ROOT/platform/.tmp_pkg"
STAGE="$STAGE_ROOT/$NAME"
MIGRATION_DIR_REL="platform/backend/src/main/resources/db/migration"
TEST_DIR_REL="platform/backend/src/test/java"
MAIN_DIR_REL="platform/backend/src/main/java"

echo "== 源码交付包 =="
echo "版本 $VER → $DIST/$NAME.{zip,tar.gz}"
echo "事实源: flyway=$FACTS_FLYWAY_VERSION migrations=$FACTS_MIGRATIONS test_classes=$FACTS_TEST_CLASSES test_cases=$FACTS_TEST_CASES java_main=$FACTS_JAVA_MAIN"

if [ "$CHECK_ONLY" = "1" ]; then
  # 交付包必须带的文件：缺一个就说明"打包脚本的 include 列表"和交付清单脱节
  MISSING=""
  for f in \
    "carbonbal-web/VERSION" \
    "carbonbal-web/index.html" \
    "carbonbal-web/tools/frontend-full.mjs" \
    "platform/backend/tools/functional-cases.py" \
    "platform/backend/tools/run-prod.sh" \
    "platform/backend/pom.xml"; do
    [ -e "$ROOT/$f" ] || MISSING="$MISSING $f"
  done
  # 最新迁移必须存在（事实源算出来的编号要能在源码里找到对应文件）
  if ! ls "$MIGRATION_DIR_REL"/V"${FACTS_FLYWAY_VERSION}"__*.sql >/dev/null 2>&1; then
    MISSING="$MISSING ${MIGRATION_DIR_REL}/V${FACTS_FLYWAY_VERSION}__*.sql"
  fi
  if [ -n "$MISSING" ]; then
    echo "[FAIL] 交付包必需文件缺失:$MISSING"
    exit 1
  fi
  # 前端同源 + 事实源非空
  bash "$ROOT/platform/tools/sync-frontend.sh" --check >/dev/null
  echo "flyway=$FACTS_FLYWAY_VERSION migrations=$FACTS_MIGRATIONS test_cases=$FACTS_TEST_CASES 必需文件齐全"
  echo "RESULT: OK (--check，未生成压缩包)"
  exit 0
fi

# 1) 前端先自检：源码与产物必须一致，否则包里的页面与源码不符
bash "$ROOT/platform/tools/sync-frontend.sh" --check

# 2) 暂存目录（每次都重建，避免上次残留混入）
rm -rf "$STAGE_ROOT"
mkdir -p "$STAGE" "$DIST"

# 3) 只复制源码与文档；排除编译产物、运行数据、备份、依赖（HANDOVER.md 直接改名为 README.md）
tar -C "$ROOT" -cf - \
  --exclude='carbonbal-web/node_modules' \
  --exclude='carbonbal-web/.tmp_*' \
  --exclude='carbonbal-web/.DS_Store' \
  --exclude='platform/backend/target' \
  --exclude='platform/backend/backup' \
  --exclude='platform/backend/data' \
  --exclude='platform/backend/logs' \
  --exclude='platform/backend/*.log' \
  --exclude='platform/.tmp_*' \
  --exclude='platform/.DS_Store' \
  --exclude='dist' \
  HANDOVER.md carbonbal-web platform | tar -C "$STAGE" -xf -

mv "$STAGE/HANDOVER.md" "$STAGE/README.md"
cp "$STAGE/carbonbal-web/VERSION" "$STAGE/VERSION"

# 4) 同源校验：对暂存区（= 将要交付的内容）重新计算，逐项与源码事实比对
stage_count() { find "$STAGE/$1" -maxdepth 1 -type f -name 'V*__*.sql' 2>/dev/null | wc -l | tr -d ' '; }
STAGE_MIGRATIONS="$(stage_count "$MIGRATION_DIR_REL")"
STAGE_FLYWAY_VERSION="$(find "$STAGE/$MIGRATION_DIR_REL" -maxdepth 1 -type f -name 'V*__*.sql' 2>/dev/null \
  | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tail -1)"
STAGE_TEST_CLASSES="$(find "$STAGE/$TEST_DIR_REL" -type f -name '*Test.java' 2>/dev/null | wc -l | tr -d ' ')"
STAGE_TEST_CASES="$(grep -rhE '^[[:space:]]*@Test\b' --include='*.java' "$STAGE/$TEST_DIR_REL" 2>/dev/null | wc -l | tr -d ' ')"
STAGE_JAVA_MAIN="$(find "$STAGE/$MAIN_DIR_REL" -type f -name '*.java' 2>/dev/null | wc -l | tr -d ' ')"
STAGE_VERSION="$(tr -d '[:space:]' < "$STAGE/VERSION")"

DRIFT=0
check() { # 名称 源码值 包内值
  if [ "$2" != "$3" ]; then
    echo "[FAIL] 同源校验失败：$1 源码=$2 包内=$3"
    DRIFT=$((DRIFT+1))
  fi
}
check "VERSION" "$FACTS_VERSION" "$STAGE_VERSION"
check "flyway 版本" "$FACTS_FLYWAY_VERSION" "$STAGE_FLYWAY_VERSION"
check "迁移个数" "$FACTS_MIGRATIONS" "$STAGE_MIGRATIONS"
check "测试类数" "$FACTS_TEST_CLASSES" "$STAGE_TEST_CLASSES"
check "测试用例数" "$FACTS_TEST_CASES" "$STAGE_TEST_CASES"
check "main 源文件数" "$FACTS_JAVA_MAIN" "$STAGE_JAVA_MAIN"

# 交付脚本齐全性（历史上出现过"包内缺 functional-cases.py"）
for required in "platform/backend/tools/functional-cases.py" "platform/backend/tools/run-prod.sh" \
                "platform/tools/lib-facts.sh" "platform/backend/tools/check-docs.sh"; do
  if [ ! -f "$STAGE/$required" ]; then
    echo "[FAIL] 交付包缺少必需文件：$required"
    DRIFT=$((DRIFT+1))
  fi
done

if [ "$DRIFT" -ne 0 ]; then
  echo "[FAIL] 同源校验未通过（$DRIFT 项），已中止打包（暂存目录保留在 $STAGE_ROOT 供排查）"
  exit 1
fi
echo "[OK] 同源校验通过（VERSION / flyway / 迁移 / 测试类 / 用例 / 源文件 / 必需脚本）"

# 5) 清单与校验和（数字全部来自事实源）
BUILT_AT="$(date '+%Y-%m-%d %H:%M:%S %z')"
FILE_COUNT="$(find "$STAGE" -type f | wc -l | tr -d ' ')"
cat > "$STAGE/MANIFEST.txt" <<EOF
project=carbonbal
version=$VER
built_at=$BUILT_AT
built_by=platform/tools/package-source.sh
facts_source=platform/tools/lib-facts.sh
files=$FILE_COUNT
java=17
spring_boot=3.2.5
mysql=8.0.28+
flyway=$FACTS_FLYWAY_VERSION
migrations=$FACTS_MIGRATIONS
test_classes=$FACTS_TEST_CLASSES
test_cases=$FACTS_TEST_CASES
java_main=$FACTS_JAVA_MAIN
description=能碳管理平台完整源码（后端 + 前端 + 迁移 + 测试与运维脚本 + 文档）
EOF

# 5b) 校验和用单进程 Python 生成，而非 `find | xargs sha256sum`
#     原因：Windows 单次 CreateProcess 的「环境变量 + 命令行」上限约 32KB，本机环境变量已占约 30KB，
#     386 个路径一次性喂给 xargs 会报 "environment is too large for exec"。Python 在进程内遍历，
#     参数长度恒定，与文件数无关。
#     注意：本机 python 是原生 Windows 版，os.path.relpath 给的是 '\'，必须归一化成 '/'，
#     否则 Linux 上 `sha256sum -c SHA256SUMS` 会因路径不匹配而全部 FAILED。
#     输出格式与 GNU sha256sum 一致：`<hash>  <相对路径>`（两空格）、无 './' 前缀、按路径排序、末尾换行。
( cd "$STAGE" && python - <<'PY'
import hashlib, os

rows = []
for root, dirs, files in os.walk('.'):
    dirs.sort()
    for name in sorted(files):
        if name == 'SHA256SUMS':
            continue
        path = os.path.join(root, name)
        rel = os.path.relpath(path, '.').replace('\\', '/')
        h = hashlib.sha256()
        with open(path, 'rb') as f:
            for chunk in iter(lambda: f.read(1 << 20), b''):
                h.update(chunk)
        rows.append(f"{h.hexdigest()}  {rel}")
rows.sort(key=lambda r: r.split('  ', 1)[1])
with open('SHA256SUMS', 'w', encoding='utf-8', newline='\n') as out:
    out.write('\n'.join(rows) + '\n')
PY
)

# 6) 归档：tar.gz（保留权限）+ zip（Windows 友好）
tar -C "$STAGE_ROOT" -czf "$DIST/$NAME.tar.gz" "$NAME"
python - "$DIST/$NAME" "$STAGE_ROOT" "$NAME" <<'PY'
import shutil, sys
base, root_dir, base_dir = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.make_archive(base, 'zip', root_dir=root_dir, base_dir=base_dir)
PY

# 7) 收尾
cd "$DIST"
SHA_TGZ=$(sha256sum "$NAME.tar.gz" | cut -d' ' -f1)
SHA_ZIP=$(sha256sum "$NAME.zip" | cut -d' ' -f1)
SIZE_TGZ=$(du -h "$NAME.tar.gz" | cut -f1)
SIZE_ZIP=$(du -h "$NAME.zip" | cut -f1)
if [ "${KEEP_TMP:-0}" = "1" ]; then
  echo "[i] KEEP_TMP=1，保留暂存目录 $STAGE_ROOT（由调用方清理）"
else
  rm -rf "$STAGE_ROOT"
fi

echo
echo "文件数 $FILE_COUNT"
echo "flyway=$FACTS_FLYWAY_VERSION migrations=$FACTS_MIGRATIONS test_classes=$FACTS_TEST_CLASSES test_cases=$FACTS_TEST_CASES"
echo "tar.gz $SIZE_TGZ  sha256=$SHA_TGZ"
echo "zip    $SIZE_ZIP  sha256=$SHA_ZIP"
echo "RESULT: OK"
