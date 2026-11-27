#!/usr/bin/env bash
# 生成 JWT 密钥（64 位随机），写入 .env.production；已存在则不覆盖
set -euo pipefail
OUT="${1:-.env.production}"
if [ -f "$OUT" ] && grep -q '^JWT_SECRET=' "$OUT"; then
  echo "已存在 $OUT 中的 JWT_SECRET，未覆盖"
  exit 0
fi
SECRET=$(openssl rand -hex 32 2>/dev/null || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
umask 077
touch "$OUT"
grep -q '^JWT_SECRET=' "$OUT" 2>/dev/null || echo "JWT_SECRET=$SECRET" >> "$OUT"
echo "已写入 $OUT：JWT_SECRET（长度 ${#SECRET}）"
echo "启动前：export \$(grep -v '^#' $OUT | xargs)"
