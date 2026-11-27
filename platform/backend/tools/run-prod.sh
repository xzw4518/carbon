#!/usr/bin/env bash
# 生产模式启动（fail-fast 校验环境变量，避免漏 JWT_SECRET 导致启动失败）
#
# 用法：
#   bash deploy/gen-secret.sh .env.production      # 首次生成 JWT_SECRET
#   set -a; . .env.production; set +a              # 或手工 export JWT_SECRET/DB_PASSWORD
#   export DB_PASSWORD='***'
#   bash tools/run-prod.sh
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

# 不自动加载 env.sh：生产必须显式注入密钥，避免误用仓库里的开发密钥
if [ -z "${JWT_SECRET:-}" ]; then
  echo "[x] 未设置 JWT_SECRET。生成：bash deploy/gen-secret.sh，然后 export JWT_SECRET=..."
  exit 2
fi
if [ "${#JWT_SECRET}" -lt 32 ]; then
  echo "[x] JWT_SECRET 长度不足 32 位（当前 ${#JWT_SECRET}），请重新生成"
  exit 2
fi
export DB_URL="${DB_URL:-jdbc:mysql://127.0.0.1:3307/carbonbal?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true}"
export DB_USER="${DB_USER:-carbonbal}"
if [ -z "${DB_PASSWORD:-}" ]; then
  echo "[x] 未设置 DB_PASSWORD"
  exit 2
fi

echo "[i] profile=prod db=${DB_URL##*/} jwt=${#JWT_SECRET} 位"
exec java ${JAVA_OPTS:--Xms256m -Xmx1g} -jar target/carbonbal.jar --spring.profiles.active=prod
