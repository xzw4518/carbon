# 能碳管理平台 · 后端

设备数据 → 碳核算 → 报告 → 资产/产值/核查/分析的服务端。对应需求卡 `../自研原型/docs/requirements/REQ-20261124-001.md`，P1–P4 已全部交付。

## 1. 环境

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | 便携版在 `../.tools/jdk-17.0.21.1+2` |
| Maven | 3.9.9 | `../.tools/apache-maven-3.9.9` |
| 数据库 | H2（dev） / MySQL 8.0（prod） | dev 用 H2 的 MySQL 兼容模式；生产 MySQL 8，utf8mb4_bin |

> **MySQL 安装路径不能含中文**：Windows 版 mysqld 会把中文目录截断成 `D:/data`，启动直接 abort。本项目把便携实例放在 `D:/dev-tools`，项目代码仍在原目录。

首次使用先加载环境：

```bash
source ../env.sh      # 设置 JAVA_HOME / PATH，不写入系统环境
```

## 2. 本地起一个真 MySQL（可选）

```bash
bash tools/mysql-dev.sh        # 首次初始化 + 启动，端口 3307，数据在 D:/dev-tools/mysql-data
```

首次建库与账号：

```sql
CREATE DATABASE carbonbal CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'carbonbal'@'%' IDENTIFIED BY 'CarbonBal@2026';
GRANT ALL PRIVILEGES ON carbonbal.* TO 'carbonbal'@'%';
```

连真库启动：

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3307/carbonbal?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USER=carbonbal DB_PASSWORD=CarbonBal@2026
java -jar target/carbonbal.jar --spring.profiles.active=prod
```

## 3. 启动

**开发（H2 内存，自动建表 + 种子数据）**

```bash
mvn spring-boot:run
# 或打包后
mvn -DskipTests package && java -jar target/carbonbal.jar
```

服务 `http://localhost:8088`。

**前端（唯一源码 → 构建产物）**

前端源码在仓库根目录 `自研原型/`，`backend/src/main/resources/static/app` 是生成产物，不要手改：

```bash
bash tools/sync-frontend.sh          # 源码同步到产物
bash tools/sync-frontend.sh --check  # 检测是否漂移
```

打包前必须先同步，否则 jar 里仍是旧页面。

**源码交付包**

```bash
bash tools/package-source.sh          # 输出 dist/carbonbal-<版本>.zip / .tar.gz
```

包含后端、前端源码、迁移、测试与运维脚本、部署配置、文档；排除 `target`、`node_modules`、`backup`、运行数据与日志。包内 `README.md` 即交接说明，含环境、启动、验收与交付边界。

**生产（MySQL 8）**

```sql
-- 首次建库：字符集与排序规则必须显式指定
CREATE DATABASE carbonbal CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'carbonbal'@'%' IDENTIFIED BY '强密码';
GRANT ALL PRIVILEGES ON carbonbal.* TO 'carbonbal'@'%';
FLUSH PRIVILEGES;
```

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/carbonbal?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USER=carbonbal
export DB_PASSWORD=强密码
export APP_JWT_SECRET=$(openssl rand -base64 48)   # 必须替换默认密钥
java -jar target/carbonbal.jar --spring.profiles.active=prod
```

> 排序规则用 `utf8mb4_bin`：设备编码、凭证编号区分大小写，避免 `EQ-A101` 与 `eq-a101` 撞唯一索引。

Flyway 启动时自动执行 `db/migration/V1__init.sql`、`V2__seed.sql`；生产首次部署请先手工确认 V2 种子是否要跳过（`spring.flyway.target=V1`）。

## 4. 演示账号

登录用手机号 + 密码（`username` 登录保留一版兼容）。演示租户：华东精密制造有限公司（`HDJM`）。

| 手机号 | 密码 | 层级 | 能做什么 |
|---|---|---|---|
| `13900000000` | `Superadmin@2026!Pass` | 超级管理员 | 只见「平台管理」：平台概览、租户开通/续期/停用/恢复、平台审计、进入任意租户代管 |
| `13800000001` | `Admin@2026!Pass` | 租户管理员 | 全部业务页面 + 系统管理（账号、角色、租户信息、审计日志）；锁定/解锁、删除与移出 |
| `13800000002` | `Test@2026!Pass` | 普通用户（运营） | 角色勾选的 34 个业务页面；可新增/编辑/流转，删除仍限管理员 |
| `13800000004` | `Test@2026!Pass` | 普通用户（运营） | 同上，核算流程中承担复核岗（职责分离按人校验） |
| `13800000005` | `Test@2026!Pass` | 普通用户（运营） | 同上，承担批准岗 |
| `13800000003` | `Test@2026!Pass` | 普通用户（运营） | 同上，演示查看者（页面集合可配） |

> 首次启动由 `DataSeeder` 用 BCrypt 写入；生产务必改密码或接入企业 SSO。
> 短信登录需真实短信通道（本期范围外），演示统一用密码登录。

## 5. 接口（177 个 API + 3 个页面入口，按域分组）

```bash
# 登录
curl -s -X POST localhost:8088/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"filler","password":"Test@2026!Pass"}'

# 之后所有请求带：-H "Authorization: Bearer <token>"
```

| 方法 | 路径 | 角色 |
|---|---|---|
| POST | `/api/auth/login` | 公开 |
| GET | `/api/auth/me` | 登录 |
| GET | `/api/devices` | 登录 |
| GET | `/api/device-readings?deviceId=&from=&to=` | 登录 |
| POST | `/api/device-readings` | admin/filler |
| PATCH | `/api/device-readings/{id}` | admin/filler |
| POST | `/api/device-readings/import`（CSV） | admin/filler |
| GET | `/api/emission-factors?fuel=&scope=&reviewStatus=` | 登录 |
| POST | `/api/emission-factors` | admin |
| POST | `/api/emission-factors/{id}/review` | admin |
| GET | `/api/accounting-periods/{period}` | 登录 |
| GET | `/api/accounting-periods/{period}/lines` | 登录 |
| POST | `/api/accounting-periods/{period}/calculate` | admin/filler |
| POST | `/api/accounting-periods/{period}/submit` | admin/filler |
| POST | `/api/accounting-periods/{period}/lock` | admin |
| POST | `/api/accounting-periods/{period}/unlock` | admin |
| POST | `/api/reports`（`{type,period}` 或 `{type:"footprint",footprintId}`） | admin/filler |
| GET | `/api/reports?type=&period=` | 登录 |
| GET | `/api/reports/{id}/download` | 登录 |
| DELETE | `/api/reports/{id}` | admin |
| GET | `/api/reports/bundle?type=&period=`（ZIP） | 登录 |
| GET | `/api/export?resource=&from=&to=&keyword=`（CSV） | 登录 |
| GET | `/api/bootstrap?year=`（原型前端启动快照） | 登录 |
| GET | `/api/audit-logs?entity=&entityId=&limit=` | 登录 |

**主数据**：`/api/products`、`/api/suppliers`（GET 分页 / POST / PATCH / DELETE）
**碳资产**：`/api/carbon-assets`、`/api/carbon-trades`（交易售出原子扣减）
**碳足迹**：`/api/footprints`（校验分阶段之和）
**预警**：`/api/alert-rules`、`/api/alert-events`、`/api/alert-records`（已读/处理/批量删除）
**节能**：`/api/energy-mgmt`、`/api/retrofits`
**设施**：`/api/facilities`（树形自关联、层级推导、删除保护）
**文件**：`/api/files`（上传/列表/下载/软删）
**产值**：`/api/production-values`、`/api/total-values`（同月同产品唯一、期间锁定守卫）
**核查**：`/api/evidences`（多附件绑定）、`/api/third-party-reports`（单文件）
**企业**：`/api/org`（GET 登录 / PATCH admin）
**分析**：`/api/overview`、`/api/analysis/{energy-consumption,carbon-emission,energy-efficiency,energy-flow,strategy,quota,footprint,supply-chain}`

分析统一返回 `{kpis, series, table, sankey?}`；桑基图仅能流分析返回。`from/to` 为 `YYYY-MM`，默认近 3 个月，最长 24 个月。

导出资源：`readings`、`production-values`、`total-values`、`evidences`、`third-party-reports`、`facilities`、`products`、`suppliers`、`carbon-assets`、`carbon-trades`、`footprints`、`alert-records`。CSV 带 UTF-8 BOM，Excel 可直接打开。

**统一信封**：`{"status":0,"body":{...}}`；错误时 HTTP 状态码 = `status`，`body.message` 为中文原因。

| 码 | 含义 |
|---|---|
| 400 | 参数校验失败（`body.detail` 为字段级错误） |
| 401 | 未登录 / 密码错误 |
| 403 | 无权限（角色不足） |
| 409 | 期间已锁定 / 版本冲突（`body.detail.currentVersion`） |
| 422 | 缺少可用排放因子 |

| 429 | 登录失败次数过多（锁定 15 分钟） |

## 6. 核算规则

```
scope1 = Σ(化石燃料活动数据 × 换算率 × 因子)      天然气 / 柴油 / 汽油
scope2 = Σ(外购电力、蒸汽活动数据 × 换算率 × 因子)
total  = scope1 + scope2          # 4 位小数，四舍五入
```

- 因子优先级：`customer`（本企业） > `local` > `national`，仅 `approved` 参与计算，且生效期覆盖核算月
- 每组明细记录实际使用的 `factor_id`，可逐条复算
- 重算 `calc_version + 1`，旧版本明细与旧报告保留
- 期间状态：`draft → calculated → submitted → locked →`（`unlock` 回 `draft`）

## 7. 迁移与回滚

```bash
mvn flyway:info          # 查看已执行版本
```

- V1 建表、V2 种子；之后按域递增：V3 文件、V10 主数据、V20 资产、V30 足迹、V40 预警、V50 节能、V55 分析种子、V60 产值、V70 核查、V80 企业档案、V90 报告多类型、V95 设施、V96 读数精度、V97 年度配额、V98 设备扩展、V100 用户体系、V101–V102 账号兼容、V103 外部授权（当前 v103）
- 生产如不需要种子数据，设 `spring.flyway.target=1`
- 回滚不做自动 undo：改数据用新迁移（V3、V4…）；结构回滚按 `docs/rollback.md` 手工执行
- 每次发布前备份：`mysqldump --single-transaction --routines carbonbal > backup-$(date +%Y%m%d).sql`

## 8. 测试

```bash
mvn test
```

128 条断言，覆盖：读往返、审计前后值、幂等、版本冲突、越权、核算口径、锁定拦截、PDF 三处一致、因子版本、解锁审批、编码唯一、超卖拒绝、冲销回滚、阶段校验、批量已读、重复处理拒绝、产值唯一性、凭证附件绑定/解绑、有效期校验、分析八页结构、能源/足迹报告、报告删除权限、企业档案乐观锁、设施树层级与删除保护、CSV 导出、报告 ZIP 打包、登录失败锁定、原型启动快照、设备 CRUD 与读数保护、年度配额 CRUD、CSV 批量导入、定时任务触发与权限、健康检查、手机号登录与用户名兼容、图形码 + 短信登录、成员 CRUD 与自我保护、核算复核/批准与职责分离、成员保护与交接、平台租户开通与停用、页面门禁（导出/导入/附件/因子/任务）、跨租户数据隔离、多租户切换与会话失效、输入健壮性（400/405/415）、主数据详情 404 语义、导入表头校验、审计日期格式校验、CSV 公式注入防护、退出登录清 Cookie、成员三项配置下线。

## 8.5 批量导入与定时任务

```bash
# 模板（浏览器也可直接用 /api/import/template?resource=products）
curl -s "$BASE/api/import/template?resource=products" -H "Authorization: Bearer $TOKEN" -o products.csv

# 导入（resource: products / production-values / carbon-assets / suppliers / facilities）
curl -s -X POST "$BASE/api/import?resource=products" -H "Authorization: Bearer $TOKEN" -F "file=@products.csv"
# → {"success":1,"failed":0,"errors":[]}
```

设备台账另有 `POST /api/devices/import`（列：设备编码,设备名称,能源种类,单位,设施编码,设备类型,级别），
设备读数用 `POST /api/device-readings/import`（列：设备编码,时间,数值,单位,换算率,来源）。

定时任务：

```bash
curl -s $BASE/api/jobs -H "Authorization: Bearer $ADMIN"                     # 列出任务
curl -s -X POST $BASE/api/jobs/scan-alerts/run -H "Authorization: Bearer $ADMIN"   # 手动跑一次（当前企业）
```

任务开关 `app.jobs.enabled`（prod 默认开）。巡检/备份/容器化见 `docs/architecture.md` §13。

## 8.6 用户体系（v100+）

- 账号 = 手机号；一人可加入多家企业，角色挂在成员关系上（admin / filler / reviewer / approver / viewer）。
- 登录双通道：手机号 + 密码，或手机号 + 短信验证码（发送前需过图形码；演示固定码 `123456`）。
- 多企业时登录先选企业；顶栏「切换企业」可随时切换，切换后前端清空快照重拉。
- 令牌默认放 HttpOnly Cookie（`cb_token`），脚本仍可用 `Authorization: Bearer`；写请求走 CSRF double-submit。
- 改密 / 重置 / 停用 / 改角色后旧令牌立即失效（`token_version`）。
- 租户端「系统管理 → 账号管理」：新增、改角色、重置密码、启停、移出、导入导出、登录日志。
- 核算流程：填报员提交 → 复核人复核 → 批准人批准 → 锁定；编制人不能复核自己，复核人不能批准自己。

演示账号：

| 手机号 | 密码 | 角色 |
|---|---|---|
| 13800000001 | Admin@2026!Pass | 管理员 |
| 13800000002 | Test@2026!Pass | 填报员 |
| 13800000004 | Test@2026!Pass | 复核人 |
| 13800000005 | Test@2026!Pass | 批准人 |
| 13800000003 | Test@2026!Pass | 查看者 |

## 8.7 平台运营与外部协作（v103）

- **平台账号**（`13900000000 / Superadmin@2026!Pass`）不属于任何企业，登录后进「平台运营 → 租户管理」：
  开通租户（返回一次性初始密码并自动建默认厂区）、停用/续期、套餐与席位、用量统计。
- **外部协作者**：成员类型选「外部协作者」，在成员行「授权」按期间或报告授权；
  外部账号登录后只看「我的授权」，其他业务接口一律 403。
- **数据范围**：成员可设「全企业 / 仅本人创建」，在产品、读数、凭证、第三方报告生效。
- **集团汇总**：「企业管理 → 集团汇总」按企业横向对比排放与能耗，自动带出同集团工厂。
- **交接与换绑**：账号页「交接」转移离职成员数据；头像菜单「换绑手机号」走双验证码。

## 9. 前端（原型接管）

单 jar 内含两套前端：

| 入口 | 内容 | 定位 |
|---|---|---|
| `/` → `/app/` | 41 个导航入口 / 38 条可渲染路由（index + assets） | 业务前端 |
| `/console.html` | 9 视图控制台 | 运维 / 调试 / 批量操作 |

原型接管方式：

- `api-client.js` 仅在 `/app/` 启用，负责登录、JWT、统一信封、401 跳登录、角色控制
- 启动顺序：登录 → `GET /api/bootstrap` 快照注入 `window.DB` → 再加载各页脚本并渲染
- 写操作已接后端：产品、产值、总值、配额/信用资产、交易、凭证、第三方报告、设施、设备读数、事件字典、改造项目、能源管理情况、供应链（14 个列表页）
- 核算闭环：新建（选月即算）→ 重算 → 提交 → 锁定 → 解锁（填原因），明细按期间读真实排放行
- 报告：carbon / energy / footprint 三类生成、下载、删除，历史列表内嵌在报告查询页
- 企业信息：管理员可编辑（乐观锁）；预警规则与记录可增删改、已读、处理、批量删除
- 8 个分析页改调 `/api/analysis/*`，KPI 与明细全部由后端聚合，不再前端自算
- 设备读数支持 CSV 导入（模板前端生成）；其余页面不显示导入入口
- 设备管理页已接真实 CRUD 与 CSV 导入（含读数保护：有读数的设备拒绝删除）
- 产品碳足迹页、年度配额页已接写接口；报告历史支持 ZIP 打包
- 数据全部来自后端：`自研原型/index.html` 必须通过服务访问（`/app/`），直接以 `file://` 打开无法登录
- 演示账号见 §4（手机号登录，6 个账号覆盖超级管理员 / 租户管理员 / 普通用户）

后续迁移：按切片把原型页面替换为平台页面（v2 起）。

## 10. 门禁

```bash
mvn test                      # 128 条断言（H2 内存库，每次独立）
bash tools/e2e.sh             # 端到端 102 项（需服务已启动）
bash tools/smoke.sh           # 上线冒烟 9 项
bash tools/data-check.sh      # L3 数据一致性 33 项（双字段/孤儿/重复/审计守恒/角色）
bash tools/perf.sh            # 性能预算 14 项（接口/体积/首屏）
bash tools/functional-cases.py # 功能用例 142 条（输出 docs/test-cases-<date>.md）
cd ../自研原型 && node tools/ui-flows.cjs  # 交互流程 38 项（真实浏览器，需服务已启动）
bash tools/inspect.sh         # 日巡检；--weekly / --monthly 换档
bash tools/check-docs.sh      # 文档一致性（过期数字/旧口径 0 处）
```

运维辅助：

```bash
bash tools/purge-test-data.sh                 # 预演清理 TST-/E2E- 测试数据
bash tools/purge-test-data.sh --apply         # 执行清理
bash tools/archive-audit.sh --days 180        # 预演审计归档
bash tools/archive-audit.sh --days 180 --apply
```

`tools/e2e.sh` 覆盖：登录、401、设备列表、设备 CRUD、年度配额、批量导入、定时任务、健康检查、核算合计、报告下载与指纹、提交/锁定、锁定拦截、解锁、四类审计动作、产值增改删与唯一冲突、凭证上传/绑定/解绑、八页分析 KPI、首页聚合、首页快照与写操作字段、前端入口、能源/足迹报告、设施树增删、企业信息、CSV 导出、报告 ZIP、登录锁定。
