#!/usr/bin/env bash
# 便携 MySQL 8 开发实例：首次初始化 + 启动（端口 3307）
# 注意：MySQL 在 Windows 上无法处理中文路径，必须放在纯 ASCII 目录（默认 D:/dev-tools）
# 用法：bash tools/mysql-dev.sh
set -euo pipefail

ROOT="${MYSQL_ROOT:-D:/dev-tools}"
HOME_DIR="$ROOT/mysql-8.0.28-winx64"
DATA="$ROOT/mysql-data"
PORT="${MYSQL_PORT:-3307}"

if [ ! -x "$HOME_DIR/bin/mysqld" ]; then
  echo "找不到 $HOME_DIR/bin/mysqld，请先解压 mysql-8.0.28-winx64.zip 到 $ROOT"
  exit 1
fi

if [ ! -d "$DATA/mysql" ]; then
  echo "[1/2] 初始化数据目录 $DATA"
  mkdir -p "$DATA"
  "$HOME_DIR/bin/mysqld" --initialize-insecure --basedir="$HOME_DIR" --datadir="$DATA" --console
else
  echo "[1/2] 数据目录已存在，跳过初始化"
fi

echo "[2/2] 启动 mysqld，端口 $PORT（Ctrl+C 停止）"
exec "$HOME_DIR/bin/mysqld" --basedir="$HOME_DIR" --datadir="$DATA" --port="$PORT" \
  --bind-address=127.0.0.1 --console --skip-log-bin
