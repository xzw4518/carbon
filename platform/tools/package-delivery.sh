#!/usr/bin/env bash
# 最终交付打包：一条命令产出 dist/<VERSION>/ 交付目录（源码包 + Maven 依赖快照 + 交付说明 + 总校验和）
#
# 用法：
#   bash platform/tools/package-delivery.sh                # → dist/<VERSION>/
#   bash platform/tools/package-delivery.sh <输出目录>       # 自定义输出目录
#   bash platform/tools/package-delivery.sh --skip-m2       # 应急：不附 Maven 依赖快照
#   bash platform/tools/package-delivery.sh --keep-tmp     # 保留暂存目录，由调用方手动清理
#
# 设计要点：
#   1) 全部先在 platform/.tmp_delivery/ 组装（该路径命中 package-source.sh 的 --exclude='platform/.tmp_*'，
#      不会被卷进源码包），全部校验通过后才把交付物搬进输出目录；失败即退出，输出目录不会出现半成品。
#   2) 源码包直接复用 package-source.sh（只传 KEEP_TMP 环境变量，不改其打包逻辑），收下它的 zip/tar.gz。
#   3) 依赖快照必须能真离线构建成功才算数：打包后用「源码包解出来的 backend + 快照解出来的 repository」
#      跑 mvn -o 实测一遍（BUILD SUCCESS 才继续），这是依赖快照有没有价值的唯一证据。
#   4) 校验和用单进程 Python 生成（Windows 单次 exec 32KB 限制，不能用 find | xargs sha256sum），
#      与 package-source.sh 保持同一套写法与输出格式。
#   5) --keep-tmp / KEEP_TMP=1：本脚本与 package-source.sh 全程不做任何批量删除，只在结尾打印
#      暂存目录路径与体积，由调用方手动删除。适用于宿主禁止脚本内批量删除的环境
#      （如注入了 safe-delete 守卫的 Windows 垫片，阈值 50 个文件/次）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=platform/tools/lib-facts.sh
. "$ROOT/platform/tools/lib-facts.sh"

VER="$FACTS_VERSION"
NAME="carbonbal-$VER"
M2_NAME="$NAME-m2-offline.tar.gz"

SKIP_M2=0
OUT_ARG=""
for a in "$@"; do
  case "$a" in
    --skip-m2) SKIP_M2=1 ;;
    --keep-tmp) KEEP_TMP=1 ;;
    -h | --help) sed -n '2,9p' "$0"; exit 0 ;;
    *) OUT_ARG="$a" ;;
  esac
done

OUT="${OUT_ARG:-$ROOT/dist/$VER}"
# 暂存目录可用 TMP_DIR 覆盖（排障/多次并行打包时指定独立目录，避免互相覆盖）
TMP="${TMP_DIR:-$ROOT/platform/.tmp_delivery}"
# 保留暂存目录模式：--keep-tmp 或环境变量 KEEP_TMP=1。
# 用于「宿主禁止脚本内批量删除」的环境（例如注入了 safe-delete 守卫的 Windows 垫片）：
# 此时本脚本与 package-source.sh 都不删任何暂存目录，改由调用方手动清理。
KEEP_TMP="${KEEP_TMP:-0}"
export KEEP_TMP

if [ "$OUT" = "/" ] || [ "$OUT" = "$ROOT" ] || [ -z "$OUT" ]; then
  echo "[FAIL] 输出目录不合法：$OUT" >&2
  exit 1
fi

cleanup() {
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    if [ "$KEEP_TMP" = "1" ]; then
      if [ -d "$TMP" ]; then
        echo "[INFO] 失败现场已保留：$TMP（手动删除即可）" >&2
      fi
    elif [ -d "$TMP" ]; then
      rm -rf "$TMP"
      echo "[INFO] 已清理暂存目录 $TMP（需保留现场排查：KEEP_TMP=1 重跑）" >&2
    fi
  fi
}
trap cleanup EXIT

fsize() { stat -c %s "$1" 2>/dev/null || wc -c < "$1" | tr -d ' '; }
mb() { awk -v s="$1" 'BEGIN{printf "%.1f", s/1048576}'; }

echo "== 最终交付包 =="
echo "版本 $VER → $OUT"
echo "事实源: flyway=$FACTS_FLYWAY_VERSION migrations=$FACTS_MIGRATIONS test_classes=$FACTS_TEST_CLASSES test_cases=$FACTS_TEST_CASES"
echo "模式: 附依赖快照=$([ "$SKIP_M2" = "1" ] && echo 否 || echo 是)"

# ---------------------------------------------------------------------------
# 0) 工具链定位：mvn 不在 PATH 时回落到仓库内 .tools/（本机为 Windows + Git Bash 环境）
# ---------------------------------------------------------------------------
MVN=""
if command -v mvn >/dev/null 2>&1; then
  MVN="$(command -v mvn)"
else
  for cand in "$ROOT"/.tools/apache-maven-*/bin/mvn; do
    if [ -x "$cand" ]; then
      MVN="$cand"
      break
    fi
  done
fi
if [ -z "$MVN" ]; then
  echo "[FAIL] 找不到 mvn：请把 Maven 加入 PATH，或放到 $ROOT/.tools/apache-maven-*/" >&2
  echo "       仅需源码包时可用 --skip-m2 跳过依赖快照" >&2
  exit 1
fi
# JAVA_HOME 未设置时回落到仓库内 JDK，并确保 java 在 PATH 上（mvn 脚本靠它找 JVM）
if ! command -v java >/dev/null 2>&1; then
  if [ -z "${JAVA_HOME:-}" ]; then
    for j in "$ROOT"/.tools/jdk-17*; do
      if [ -x "$j/bin/java" ]; then
        export JAVA_HOME="$j"
        break
      fi
    done
  fi
  if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
fi
if ! command -v java >/dev/null 2>&1; then
  echo "[FAIL] 找不到 java：请装 JDK 17 并设置 JAVA_HOME，或放到 $ROOT/.tools/jdk-17*/" >&2
  exit 1
fi
echo "工具链: mvn=$MVN"
echo "        java=$(command -v java)"

# ---------------------------------------------------------------------------
# 1) 暂存目录（每次重建；保留模式下不删任何东西，只要求它是空的）
# ---------------------------------------------------------------------------
if [ "$KEEP_TMP" = "1" ]; then
  if [ -e "$TMP" ] && [ -n "$(ls -A "$TMP" 2>/dev/null || true)" ]; then
    echo "[FAIL] 保留暂存区模式要求 $TMP 为空：请先手动删除该目录后重跑" >&2
    exit 1
  fi
  mkdir -p "$TMP"
else
  rm -rf "$TMP"
  mkdir -p "$TMP"
fi

# ---------------------------------------------------------------------------
# 2) 源码包：复用 package-source.sh，把它产出的两个归档收进交付目录
# ---------------------------------------------------------------------------
echo
echo "-- [1/4] 生成源码包（复用 package-source.sh）--"
bash "$ROOT/platform/tools/package-source.sh" "$TMP"
for f in "$NAME.zip" "$NAME.tar.gz"; do
  if [ ! -f "$TMP/$f" ]; then
    echo "[FAIL] package-source.sh 未产出 $f" >&2
    exit 1
  fi
done

# ---------------------------------------------------------------------------
# 3) Maven 依赖快照 + 离线构建实测
# ---------------------------------------------------------------------------
M2_NOTE=""
if [ "$SKIP_M2" = "1" ]; then
  M2_SIZE=0
  M2_NOTE="本次为应急模式（--skip-m2），未附依赖快照，路径 B 需联网构建。"  echo
  echo "-- [2/4] 跳过 Maven 依赖快照（--skip-m2）--"
else
  echo
  echo "-- [2/4] 生成 Maven 依赖快照 --"

  go_offline() {
    echo "   mvn dependency:go-offline（补齐依赖与插件）"
    ( cd "$ROOT/platform/backend" && "$MVN" -q -B dependency:go-offline )
  }

  make_m2_tar() {
    if [ ! -d "$HOME/.m2/repository" ]; then
      echo "[FAIL] 本机没有 $HOME/.m2/repository，无法生成依赖快照" >&2
      exit 1
    fi
    echo "   打包 $HOME/.m2/repository → $M2_NAME"
    tar -czf "$TMP/$M2_NAME" -C "$HOME/.m2" repository
  }

  # 真离线构建：源码包解出的 backend + 快照解出的 repository，全程 -o（不联网）
  # 每次实测用独立子目录（.m2test1 / .m2test2），保留模式下不做任何删除。
  OFF_ATTEMPT=0
  offline_build_check() {
    OFF_ATTEMPT=$((OFF_ATTEMPT + 1))
    local test="$TMP/.m2test$OFF_ATTEMPT"
    local log="$TMP/offline-build$OFF_ATTEMPT.log"
    local repo repo_arg bdir
    rm -rf "$test"
    mkdir -p "$test"
    tar -xzf "$TMP/$NAME.tar.gz" -C "$test"
    tar -xzf "$TMP/$M2_NAME" -C "$test"
    repo="$test/repository"
    repo_arg="$repo"
    # 原生 Windows 版 mvn 不认 MSYS 风格 /d/... 路径，转成 D:\...
    if command -v cygpath >/dev/null 2>&1; then
      repo_arg="$(cygpath -w "$repo")"
    fi
    bdir="$test/$NAME/platform/backend"
    echo "   离线构建实测：mvn -o -Dmaven.repo.local=$repo_arg -DskipTests clean package"
    if ( cd "$bdir" && "$MVN" -o -B -DskipTests -Dmaven.repo.local="$repo_arg" clean package >"$log" 2>&1 ); then
      grep -E "BUILD SUCCESS|Total time" "$log" | tail -2
      echo "   产物: $(ls -l "$bdir/target/"*.jar 2>/dev/null | awk '{print $5" bytes  "$9}' | head -1)"
      if [ "$KEEP_TMP" != "1" ]; then
        rm -rf "$test"
      fi
      return 0
    fi
    echo "[WARN] 离线构建失败，最后 20 行日志：" >&2
    tail -20 "$log" >&2
    return 1
  }

  # 在线补齐 go-offline 的已知缺口：只在一个源码副本里构建，绝不碰 platform/backend/target
  # （本机 8088 正在跑 target/carbonbal.jar，clean 会删正在使用的 jar）
  fill_m2_online() {
    local test="$TMP/.m2fill"
    echo "   在线构建一次以补齐快照缺口（在源码副本内进行，不影响运行中的服务）"
    rm -rf "$test"
    mkdir -p "$test"
    tar -xzf "$TMP/$NAME.tar.gz" -C "$test"
    ( cd "$test/$NAME/platform/backend" && "$MVN" -q -B -DskipTests clean package )
    if [ "$KEEP_TMP" != "1" ]; then
      rm -rf "$test"
    fi
  }

  go_offline
  make_m2_tar
  if ! offline_build_check; then
    fill_m2_online
    make_m2_tar
    if ! offline_build_check; then
      echo "[FAIL] 依赖快照无法支持离线构建，已中止（这份快照交付出去没有价值）" >&2
      exit 1
    fi
  fi
  M2_SIZE="$(fsize "$TMP/$M2_NAME")"
  echo "   依赖快照: $(mb "$M2_SIZE") MB"
  M2_NOTE="本次已附依赖快照：按上面命令加 -o 与 -Dmaven.repo.local 即可完全离线构建（发布前已用本快照实测 BUILD SUCCESS）。"
fi

# ---------------------------------------------------------------------------
# 4) DELIVERY.md（ASCII 文件名、中文内容；大小用占位符后填，避免 heredoc 里出现反引号/$
# ---------------------------------------------------------------------------
echo
echo "-- [3/4] 生成 DELIVERY.md --"
BUILT_AT="$(date '+%Y-%m-%d %H:%M:%S %z')"
SIZE_ZIP="$(fsize "$TMP/$NAME.zip")"
SIZE_TGZ="$(fsize "$TMP/$NAME.tar.gz")"

if [ "$SKIP_M2" = "1" ]; then
  M2_ROW="| （本次未附） | 应急模式 --skip-m2，未生成依赖快照 | — | — |"
else
  M2_ROW="| $M2_NAME | Maven 依赖与插件快照（本机 ~/.m2/repository 的完整副本），内网构建用 | $(mb "$M2_SIZE") MB | 甲方开发/运维，无外网环境 |"
fi

cat >"$TMP/DELIVERY.md" <<'MD'
# 能碳管理平台 @VER@ 交付包

生成时间：@BUILT_AT@
版本来源：carbonbal-web/VERSION（事实源 platform/tools/lib-facts.sh）
数据库：Flyway @FLYWAY_VERSION@（@MIGRATIONS@ 个迁移）首次启动自动建表；演示数据由 Seeder 生成

> 数据库只交「表结构 + 演示数据」：不包含任何本机历史数据导出，也不需要手工建表或导入 SQL 文件。

## 1. 包清单

| 文件 | 是什么 | 大小 | 给谁用 |
|---|---|---|---|
| @ZIP@ | 完整源码包：后端（Spring Boot）+ 前端源码 + 数据库迁移 + 测试 + 运维脚本 + 文档，Windows 友好 | @SIZE_ZIP@ MB | 甲方开发/运维（Windows） |
| @TGZ@ | 与 zip 内容相同，保留文件权限与中文文件名，Linux 首选 | @SIZE_TGZ@ MB | 甲方开发/运维（Linux） |
@M2_ROW@
| DELIVERY.md | 本文件：部署两条路径、数据库说明、校验方式 | < 10 KB | 甲方开发/运维 |
| SHA256SUMS | 上面各文件的 SHA-256（sha256sum 格式） | < 10 KB | 甲方用于完整性校验 |

源码包顶层目录：`@NAME@/`，内含 `README.md`（部署与账号说明）、`carbonbal-web/`（前端源码，已与后端静态产物一致）、`platform/`（后端、迁移、部署脚本）。

## 2. 部署路径 A：Docker（推荐）

前置：Docker Desktop / Docker Engine 20.10+、docker compose v2。

```bash
tar -xzf @TGZ@
cd @NAME@/platform
bash deploy/gen-secret.sh .env.production         # 生成 JWT_SECRET 并写入 .env.production（已存在则不覆盖）
# 编辑 .env.production，补一行：DB_PASSWORD=你的MySQL强密码
docker compose -f deploy/docker-compose.yml up -d --build
```

说明：
- compose 会拉起 MySQL 8.0（utf8mb4）与应用两个容器，应用等 MySQL 健康检查通过后才启动，Flyway 随即自动建表。
- 首次 `--build` 需要能访问 Docker Hub（拉基础镜像）。

启动验证：

```bash
curl http://localhost:8088/api/health        # 期望 {"status":"UP",...}
```

浏览器访问：`http://<服务器IP>:8088/app/`，用源码包 `README.md`「体验账号」一节里的账号登录。

## 3. 部署路径 B：手工部署 + 离线依赖

前置：JDK 17、MySQL 8.0.28+（建库 carbonbal，字符集 utf8mb4）。

```bash
tar -xzf @TGZ@
tar -xzf @M2NAME@                     # 解出 repository/，这就是离线依赖仓库
cd @NAME@/platform/backend
mvn -o -Dmaven.repo.local=<上一步 repository 的绝对路径> clean package -DskipTests
```

运行（按实际环境替换尖括号内容；DB_URL 里有 & 符号，整段用单引号包住）：

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/carbonbal?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USER=carbonbal
export DB_PASSWORD='<MySQL强密码>'
export JWT_SECRET='<用 deploy/gen-secret.sh 生成，或 openssl rand -hex 32>'
java -jar target/carbonbal.jar --spring.profiles.active=prod
```

@M2_NOTE@

能上网时可以省掉 `-o` 与 `-Dmaven.repo.local`，首次构建会从 Maven 中央仓库拉取依赖。国内网络较慢时可在 `~/.m2/settings.xml` 加阿里云镜像：

```xml
<mirror>
  <id>aliyun</id>
  <mirrorOf>central</mirrorOf>
  <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

## 4. 数据库说明

- **表结构**：由 @MIGRATIONS@ 个 Flyway 迁移（最高版本 V@FLYWAY_VERSION@）在应用首次启动时自动创建/升级。**不需要手工建表**，也不要在库里手工改表结构——下次启动 Flyway 校验会失败。
- **演示数据**：由 Seeder 生成（配置项 `SEED_ENABLED`，默认 true）。演示结束建议设为 `SEED_ENABLED=false`；Seeder 本身幂等，重复启动不会重复插入。
- **只交表结构 + 演示数据**：包含 0 行本机历史业务数据。
- **密码注入**：数据库密码只能通过 `DB_PASSWORD` 环境变量注入，配置文件里没有默认口令。未注入时应用启动失败属**预期的安全保护**，不是缺陷。
- 版本要求：MySQL 8.0.28+，库与表统一 utf8mb4 / utf8mb4_unicode_ci。

## 5. 初始账号

见源码包内 `README.md` 的「体验账号 / 初始账号」一节（首次启动自动创建）。本文件不重复明文口令。

生产交付后第一件事：改所有演示账号密码、更换 `JWT_SECRET`、把 `SEED_ENABLED` 设为 false。

## 6. 已知注意事项

1. **中文文件名文档**：`carbonbal-web/docs/` 下有 3 个中文名文档（`发布与回滚.md`、`字段与口径.md`、`需求卡模板.md`）。在 Linux 上若使用老版 Info-ZIP `unzip`（6.0x）解压 zip，这 3 个文件名可能变成乱码，进而让 `sha256sum -c SHA256SUMS` 报 3 条 FAILED（**文件内容无损，仅为文件名编码问题**）。建议优先使用 tar.gz，或用 `unzip -O UTF-8`、`python -m zipfile -e` 解压 zip。
2. **JWT_SECRET**：必须用 `deploy/gen-secret.sh` 或 `openssl rand -hex 32` 生成，不要沿用任何示例值；更换后已签发的令牌全部失效（需重新登录）。
3. **端口与健康检查**：默认 8088；`/api/health` 返回 `status=UP` 才算启动完成，容器启动后先等 Flyway 跑完。
4. **演示数据治理**：演示账号与演示数据仅用于验收演示，正式上线前请清理或改密。

## 7. 完整性校验

```bash
sha256sum -c SHA256SUMS          # 每个文件都应输出 OK
```

本目录下每个文件都登记在 SHA256SUMS 中；任一文件校验失败请重新获取交付包，不要使用损坏的文件部署。
MD

# 占位符替换（用 # 作分隔符：内容里含 | 与 /，不能用作 sed 分隔符）
sed -i \
  -e "s#@VER@#${VER}#g" \
  -e "s#@NAME@#${NAME}#g" \
  -e "s#@ZIP@#${NAME}.zip#g" \
  -e "s#@TGZ@#${NAME}.tar.gz#g" \
  -e "s#@M2NAME@#${M2_NAME}#g" \
  -e "s#@SIZE_ZIP@#$(mb "$SIZE_ZIP")#g" \
  -e "s#@SIZE_TGZ@#$(mb "$SIZE_TGZ")#g" \
  -e "s#@BUILT_AT@#${BUILT_AT}#g" \
  -e "s#@FLYWAY_VERSION@#${FACTS_FLYWAY_VERSION}#g" \
  -e "s#@MIGRATIONS@#${FACTS_MIGRATIONS}#g" \
  -e "s#@M2_NOTE@#${M2_NOTE}#g" \
  -e "s#@M2_ROW@#${M2_ROW}#g" \
  "$TMP/DELIVERY.md"

if grep -q '@[A-Z_]*@' "$TMP/DELIVERY.md"; then
  echo "[FAIL] DELIVERY.md 仍有未替换的占位符：" >&2
  grep -n '@[A-Z_]*@' "$TMP/DELIVERY.md" >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# 5) SHA256SUMS 只登记交付目录里的交付物本身（不遍历暂存区：保留模式下 .m2test* 会混进来）
#    单进程 Python，避免 xargs 参数超限；路径统一正斜杠、按路径排序、末尾换行，与 GNU sha256sum 一致。
# ---------------------------------------------------------------------------
EXPECT_FILES="$NAME.zip $NAME.tar.gz DELIVERY.md SHA256SUMS"
if [ "$SKIP_M2" != "1" ]; then
  EXPECT_FILES="$EXPECT_FILES $M2_NAME"
fi
echo "-- [4/4] 生成 SHA256SUMS 并做完整性自检 --"
(
  cd "$TMP"
  DELIVERABLES="$EXPECT_FILES" python - <<'PY'
import hashlib, os

names = [n for n in os.environ['DELIVERABLES'].split() if n and n != 'SHA256SUMS']
rows = []
for name in names:
    h = hashlib.sha256()
    with open(name, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    rows.append(f"{h.hexdigest()}  {name.replace(os.sep, '/')}")
rows.sort(key=lambda r: r.split('  ', 1)[1])
with open('SHA256SUMS', 'w', encoding='utf-8', newline='\n') as out:
    out.write('\n'.join(rows) + '\n')
PY
)

# 自检：交付目录里必须有这几个文件（清单见上），且校验和能对上
for f in $EXPECT_FILES; do
  if [ ! -f "$TMP/$f" ]; then
    echo "[FAIL] 交付目录缺少 $f" >&2
    exit 1
  fi
done
(
  cd "$TMP"
  sha256sum -c SHA256SUMS >"$TMP/.sha-check.log" 2>&1 || {
    echo "[FAIL] SHA256SUMS 自检失败：" >&2
    cat "$TMP/.sha-check.log" >&2
    exit 1
  }
)
NFILES="$(grep -c ': OK$' "$TMP/.sha-check.log" || true)"
EXPECT_N="$(wc -l <"$TMP/SHA256SUMS" | tr -d ' ')"
rm -f "$TMP/.sha-check.log"
if [ "$NFILES" != "$EXPECT_N" ]; then
  echo "[FAIL] SHA256SUMS 自检不完整：通过 $NFILES 项，应为 $EXPECT_N 项" >&2
  exit 1
fi
echo "   校验通过 $NFILES/$EXPECT_N 项"

# ---------------------------------------------------------------------------
# 6) 落盘：整体 mv（在此之前输出目录不会有任何半成品）
# ---------------------------------------------------------------------------
mkdir -p "$(dirname "$OUT")"
if [ -e "$OUT" ]; then
  if [ "$KEEP_TMP" = "1" ]; then
    # 保留模式不删除已有目录（目录里应该只有上次的交付物）：逐个文件覆盖，不触发批量删除
    echo "   保留暂存区模式：不删除已有输出目录 $OUT，交付物逐个覆盖"
  else
    echo "   覆盖已有输出目录：$OUT"
    rm -rf "$OUT"
  fi
fi
mkdir -p "$OUT"
# 只搬交付物，不把 .m2test* 等中间目录带进交付目录
for f in $EXPECT_FILES; do
  mv "$TMP/$f" "$OUT/$f"
done
if [ "$KEEP_TMP" = "1" ]; then
  KEEP_SIZE="$(du -sh "$TMP" 2>/dev/null | cut -f1)"
  echo "   暂存目录已保留：$TMP（约 $KEEP_SIZE），请手动删除"
  if [ -d "$ROOT/platform/.tmp_pkg" ]; then
    PKG_SIZE="$(du -sh "$ROOT/platform/.tmp_pkg" 2>/dev/null | cut -f1)"
    echo "   暂存目录已保留：$ROOT/platform/.tmp_pkg（约 $PKG_SIZE），请手动删除"
  fi
else
  rm -rf "$TMP"
fi

SRC_TOTAL=$((SIZE_ZIP + SIZE_TGZ))
if [ "$SKIP_M2" = "1" ]; then
  M2_MB="0.0"
  ALL_TOTAL=$((SIZE_ZIP + SIZE_TGZ))
else
  M2_MB="$(mb "$M2_SIZE")"
  ALL_TOTAL=$((SIZE_ZIP + SIZE_TGZ + M2_SIZE))
fi
NFILES_ALL="$(find "$OUT" -maxdepth 1 -type f | wc -l | tr -d ' ')"

echo
echo "交付目录 $OUT"
for f in $(cd "$OUT" && ls -1); do
  printf "  %-38s %7s MB\n" "$f" "$(mb "$(fsize "$OUT/$f")")"
done
echo "  合计 $(mb "$ALL_TOTAL") MB / $NFILES_ALL 个文件"
echo
echo "RESULT: OK ver=$VER source=$(mb "$SRC_TOTAL")MB m2=${M2_MB}MB files=$NFILES_ALL"
