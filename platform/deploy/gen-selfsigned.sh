#!/usr/bin/env bash
# 生成自签证书（内网/试用环境）；正式环境请用 CA 证书替换
set -euo pipefail
DIR="${1:-deploy/certs}"
DOMAIN="${2:-localhost}"
mkdir -p "$DIR"
openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
  -keyout "$DIR/privkey.pem" -out "$DIR/fullchain.pem" \
  -subj "/C=CN/O=CarbonBal/CN=$DOMAIN" \
  -addext "subjectAltName=DNS:$DOMAIN,DNS:localhost,IP:127.0.0.1" 2>/dev/null
chmod 600 "$DIR/privkey.pem"
echo "已生成：$DIR/fullchain.pem / $DIR/privkey.pem（域名 $DOMAIN，有效期 825 天）"
