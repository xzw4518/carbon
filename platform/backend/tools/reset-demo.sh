#!/usr/bin/env bash
# 演示数据复位：核算期间退回草稿 + 清理 TST 残留（详见 reset_demo.py）
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
if ! command -v python >/dev/null 2>&1; then
  echo "需要 python（已用于功能用例集）"; exit 2
fi
exec python tools/reset_demo.py "$@"
