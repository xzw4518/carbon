#!/usr/bin/env bash
# 依赖安全审计骨架（P2-8）：SAST/SCA 的统一入口。
#
# ⚠️ 需要联网（Maven Central / npm registry / NVD 镜像）与工具链授权。
#    本仓库当前环境**未联网实测**，所以：
#      · 工具缺失 → 打印 [SKIP] 并以退出码 2 结束（不冒充通过）；
#      · 任一扫描发现高危（CVSS ≥ 7）→ 退出码 1。
#
# 用法：
#   bash platform/tools/deps-audit.sh                # 后端 + 前端
#   bash platform/tools/deps-audit.sh --backend      # 只跑后端
#   bash platform/tools/deps-audit.sh --frontend     # 只跑前端
#   OWASP_DC_ARGS="--failOnCVSS 7" bash platform/tools/deps-audit.sh
#
# 建议接入节奏：发布前在能联网的构建机上跑一次，把 reports/ 归档到交付包外。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2

DO_BE=1; DO_FE=1; SKIPPED=0; FAILED=0
case "${1:-all}" in
  --backend) DO_FE=0 ;;
  --frontend) DO_BE=0 ;;
  all) ;;
  *) echo "未知参数：$1"; exit 2 ;;
esac

report_dir="$ROOT/platform/.deps-audit"
mkdir -p "$report_dir"

net_ok() {
  curl -sf -m 6 -o /dev/null https://repo.maven.apache.org/maven2/ 2>/dev/null \
    || curl -sf -m 6 -o /dev/null https://registry.npmjs.org/ 2>/dev/null
}

if ! net_ok; then
  echo "[SKIP] 无外网（Maven Central / npm registry 均不可达）：SAST/SCA 未执行"
  echo "       联网后重跑：bash platform/tools/deps-audit.sh"
  exit 2
fi

# ---------- 后端：OWASP dependency-check ----------
if [ "$DO_BE" = "1" ]; then
  echo "== 后端依赖扫描（OWASP dependency-check）=="
  if (cd platform/backend && mvn -B -q org.owasp:dependency-check-maven:9.0.9:check \
        ${OWASP_DC_ARGS:--DfailBuildOnCVSS=7} \
        -Dformat=HTML -DoutputDirectory="$report_dir" > "$report_dir/backend.log" 2>&1); then
    echo "[PASS] 后端依赖无 CVSS ≥ 7 漏洞（报告：$report_dir）"
  else
    echo "[FAIL] 后端依赖扫描失败或有高危：见 $report_dir/backend.log"
    FAILED=$((FAILED+1))
  fi
fi

# ---------- 前端：npm audit ----------
if [ "$DO_FE" = "1" ]; then
  echo "== 前端依赖扫描（npm audit）=="
  if [ -f carbonbal-web/package-lock.json ]; then
    if (cd carbonbal-web && npm audit --audit-level=high --json > "$report_dir/frontend-audit.json" 2>/dev/null); then
      echo "[PASS] 前端依赖无 high/critical"
    else
      echo "[FAIL] 前端依赖存在 high/critical（见 $report_dir/frontend-audit.json）"
      FAILED=$((FAILED+1))
    fi
  else
    echo "[SKIP] carbonbal-web/package-lock.json 不存在：跳过 npm audit"
    SKIPPED=$((SKIPPED+1))
  fi
fi

# ---------- SAST（可选）：Semgrep ----------
echo "== SAST（Semgrep）=="
if command -v semgrep >/dev/null 2>&1; then
  if semgrep --config=auto --error --json -o "$report_dir/semgrep.json" platform/backend/src carbonbal-web/assets >/dev/null 2>&1; then
    echo "[PASS] Semgrep 无 error 级发现"
  else
    echo "[FAIL] Semgrep 发现 error 级问题（见 $report_dir/semgrep.json）"
    FAILED=$((FAILED+1))
  fi
else
  echo "[SKIP] 未安装 semgrep：pip install semgrep 后重跑"
  SKIPPED=$((SKIPPED+1))
fi

echo
if [ "$FAILED" -gt 0 ]; then
  echo "RESULT: FAIL ($FAILED 项)"
  exit 1
elif [ "$SKIPPED" -gt 0 ]; then
  echo "RESULT: PARTIAL ($SKIPPED 项跳过，未计入通过)"
  exit 0
fi
echo "RESULT: PASS"
