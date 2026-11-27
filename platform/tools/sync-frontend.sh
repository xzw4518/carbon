#!/usr/bin/env bash
# 前端构建同步：自研原型/ 是唯一源码目录，backend/src/main/resources/static/app 是构建产物。
# 用法：
#   bash tools/sync-frontend.sh          同步源码到产物
#   bash tools/sync-frontend.sh --check  只检查是否漂移（有差异退出码 1）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/自研原型"
DST="$ROOT/platform/backend/src/main/resources/static/app"

[ -f "$SRC/index.html" ] || { echo "源码目录缺少 index.html: $SRC" >&2; exit 1; }
[ -d "$SRC/assets" ] || { echo "源码目录缺少 assets: $SRC" >&2; exit 1; }

if [ "${1:-}" = "--check" ]; then
  if diff -r -q "$SRC/index.html" "$DST/index.html" >/dev/null 2>&1 \
     && diff -r -q "$SRC/assets" "$DST/assets" >/dev/null 2>&1; then
    echo "前端产物与源码一致"
    exit 0
  fi
  echo "前端产物已漂移，请执行 bash tools/sync-frontend.sh" >&2
  diff -r -q "$SRC/assets" "$DST/assets" 2>/dev/null | head -20 >&2 || true
  exit 1
fi

mkdir -p "$DST"
rm -rf "$DST/assets"
cp -r "$SRC/assets" "$DST/assets"
cp "$SRC/index.html" "$DST/index.html"
echo "已同步：$SRC -> $DST"
