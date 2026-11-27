# 能碳管理平台（源码交付包）

> 版本 1.0.1　打包日期见 `MANIFEST.txt`　Flyway 115（33 个迁移）　技术栈 JDK 17 + Spring Boot 3.2 + MySQL 8
> 本包为**完整源码**：后端、前端、数据库迁移、测试与验收脚本、部署配置、项目文档。不含编译产物、运行数据与备份。

## 1. 包内结构

```
carbonbal-1.0.1/
├─ README.md                    本文件
├─ VERSION                      版本号
├─ MANIFEST.txt                 打包信息（版本/日期/文件数）
├─ SHA256SUMS                   逐文件校验和
├─ carbonbal-web/                     前端源码（唯一源码目录）
│  ├─ index.html
│  ├─ assets/                   JS/CSS（无框架、零构建）
│  ├─ docs/                     需求卡、字段口径、前端发布约束
│  ├─ tools/                    L0 静态门禁、UI 门禁、首屏性能采样
│  └─ AGENTS.md                 项目硬约束（S3 档位、R11–R14）
└─ platform/
   ├─ README.md                 后端说明与接口清单
   ├─ env.example.sh            环境变量模板
   ├─ backend/
   │  ├─ pom.xml
   │  ├─ src/main/java/         业务代码（认证/租户/核算/报告/预警…）
   │  ├─ src/main/resources/
   │  │  ├─ application.yml     dev（H2）与 prod（MySQL）两套配置
   │  │  ├─ db/migration/       Flyway V1–V115
   │  │  └─ static/             app（前端产物，可重新生成）、console.html（运维台）
   │  ├─ src/test/java/         128 条断言（H2，32 个测试类）
   │  └─ tools/                 e2e/smoke/数据门禁/性能/巡检/备份/恢复/打包脚本
   ├─ deploy/                   Dockerfile、compose、nginx、自签证书、密钥生成
   └─ docs/                     架构、权限、状态机、错误码、发布回滚、复盘、模板库
```

## 2. 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | 必须；`JAVA_HOME` 指向 JDK 17 |
| Maven | 3.9+ | 构建与测试 |
| MySQL | 8.0.28+ | 生产运行；字符集 `utf8mb4` / `utf8mb4_bin` |
| Node.js | 18+（可选） | 仅前端门禁与首屏性能采样需要 `playwright-core` |
| 系统 | Linux / Windows / macOS | 脚本为 Bash；Windows 用 Git Bash |

## 3. 五分钟体验（H2 内存库，无需 MySQL）

```bash
cd platform/backend
mvn clean package -DskipTests
java -jar target/carbonbal.jar
# 浏览器打开 http://localhost:8088/app/
```

默认 `dev` 配置：H2 内存库、Flyway 自动建表、种子数据开启、短信验证码回显。**重启即重置数据**，仅用于体验与联调。

体验账号（首次启动自动创建，生产必须立即改密）：

| 手机号 | 密码 | 角色 |
|---|---|---|
| 13800000001 | Admin@2026!Pass | 管理员 |
| 13800000002 | Test@2026!Pass | 填报员 |
| 13800000003 | Test@2026!Pass | 查看者 |
| 13800000004 | Test@2026!Pass | 复核人 |
| 13800000005 | Test@2026!Pass | 批准人 |
| 13900000000 | Superadmin@2026!Pass | 平台运营（租户开通） |

## 4. 生产运行（MySQL）

```bash
# 1) 建库（必须 utf8mb4_bin，否则排序/唯一性与迁移不一致）
mysql -uroot -p <<'SQL'
CREATE DATABASE carbonbal CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'carbonbal'@'%' IDENTIFIED BY '<强密码>';
GRANT ALL PRIVILEGES ON carbonbal.* TO 'carbonbal'@'%';
FLUSH PRIVILEGES;
SQL

# 2) 配置环境变量（参考 platform/env.example.sh）
export DB_URL='jdbc:mysql://127.0.0.1:3306/carbonbal?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USER=carbonbal
export DB_PASSWORD='<强密码>'
bash platform/deploy/gen-secret.sh platform/.env.production
set -a; . platform/.env.production; set +a

# 3) 构建并启动（Flyway 自动执行 33 个迁移并建种子）
cd platform/backend
mvn clean package -DskipTests
java -jar target/carbonbal.jar --spring.profiles.active=prod
```

生产注意：

- `JWT_SECRET` 必须 ≥32 位且非默认值，否则启动失败（这是刻意强校验）。
- 首次启动后把 `SEED_ENABLED` 设为 `false`，避免重复种子；演示账号立即改密或删除。
- 短信为模拟通道（验证码写日志）；**实名短信通道不在本次交付范围**，由客户环境自行接入（替换 `SmsService` 发送实现并配置云厂商模板）。
- 域名、证书与 HTTPS 由客户环境负责；包内 `platform/deploy/nginx*.conf`、自签脚本仅作参考。
- 升级/回滚见 `platform/docs/release-and-rollback.md`（备份优先，不执行 down 迁移）。

## 5. 前端开发

`carbonbal-web/` 是唯一源码，`platform/backend/src/main/resources/static/app` 是构建产物：

```bash
bash platform/tools/sync-frontend.sh           # 源码 → 产物
bash platform/tools/sync-frontend.sh --check   # 检查漂移（交付前必须一致）
```

改前端后不跑同步，打出来的 jar 仍是旧页面。包内已包含同步后的产物，可直接构建。

## 6. 验收命令（在 `platform/backend` 下执行）

| 命令 | 期望 | 说明 |
|---|---|---|
| `mvn test` | 128/128 | H2 内存库，每次独立 |
| `bash tools/smoke.sh` | 9/9 | 需服务已启动 |
| `bash tools/e2e.sh` | 100/102 | 需服务；2 项短信用例依赖通道配额，本次可标环境豁免 |
| `bash tools/data-check.sh` | 29/29 | 直连库；需 `MYSQL_BIN` 或 mysql 客户端 |
| `bash tools/perf.sh` | 14/14 | 接口耗时/体积/首屏预算 |
| `bash tools/check-docs.sh` | 0 处 | 活文档过期口径 |
| `bash tools/inspect.sh` | 无 FAIL | 日巡检；`--weekly` / `--monthly` 换档 |

前端门禁（在 `carbonbal-web` 下，需先 `npm i`）：

```bash
node tools/gate-l0.cjs     # 11/11
node tools/check-ui.cjs    # 5/5，登录真实服务遍历 38 路由
```

> e2e 的短信两条用例需要 `SMS_EXPOSE_CODE=true`（演示配置默认开启）且当日配额未耗尽。短信实名通道不在本次范围，验收时这两项可标注环境豁免，其余 100 项必须全过。

## 7. 常用脚本

| 脚本 | 用途 |
|---|---|
| `tools/backup.sh <目录> <保留天数>` | MySQL 全量 + 上传文件备份 |
| `tools/restore.sh <备份>` | 恢复演练 |
| `tools/monitor.sh` | 存活探针（可挂 cron） |
| `tools/archive-audit.sh --days 180` | 审计归档（默认预演，`--apply` 执行） |
| `tools/purge-test-data.sh` | 清理 `TST-` 测试数据（默认预演） |
| `tools/reset-demo.sh` | 演示复位：核算期间退回草稿 + 清理测试残留（`ui-flows` 已自动调用） |
| `tools/run-prod.sh` | 生产模式启动：先校验 `JWT_SECRET`（≥32 位）与 `DB_PASSWORD`，缺失时直接给出提示而不是启动到一半失败 |
| `tools/package-source.sh` | 重新生成本源码包 |

## 8. 交付边界与已知限制

- **权限模型边界**：本期权限为「页面集合 + 前端按角色隐藏按钮」，接口层只区分 `ADMIN / USER`（删除类操作限 ADMIN），**未内置只读角色，也未实现动作位（读/写/审批）**。演示账号 `viewer` 只代表较小的页面集合，其在页面看不到操作按钮，但绕过页面直接调用接口仍可写入；对外演示请以 UI 操作路径为准，如需真正的只读角色需后续增加权限点。参见 `platform/docs/user-system-redesign.md` §权限。
- **初始口令与密码策略**：交付账号口令均为强口令（15~21 位，含大小写字母、数字与符号），满足平台 ≥12 位改密策略；仍建议首次登录后按需更换。
- 短信实名通道、域名与 HTTPS 证书接入：**不在本次交付范围**，由客户环境负责；包内保留模拟通道与参考配置。
- 性能基线为开发机 localhost，不含公网 RTT；未做并发压测。
- 碳足迹报告的阶段明细表未恢复（后端暂无阶段明细接口）。
- 核算标准三列为内置常量 `GB/T 32151.9`，按企业配置需新增标准主数据表。
- 审计归档未在真实超期数据上演练（当前数据不足 180 天）。
- 机制层面 M09 独立错误页、M14 FAIL 五类归因、M17 首页结论句仍为部分达标。

## 9. 交接核对

1. `sha256sum -c SHA256SUMS` 全部 OK。
2. `MANIFEST.txt` 的版本/文件数与实际一致。
3. 解压后按第 3 节能启动、能登录、能打开 14 个台账。
4. 按第 6 节跑 `mvn test` 与 `smoke`，结果与表中一致。
5. 收到后第一件事：改演示账号密码、换 `JWT_SECRET`、关 `SEED_ENABLED`。

问题库与复盘在 `platform/docs/problem-library.md`、`platform/docs/retrospectives/`，接手前建议先过一遍。
