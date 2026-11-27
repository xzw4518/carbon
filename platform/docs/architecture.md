# 平台后端架构（v1）

> 目标：把原型 33 页对应的业务全部后端化，前端可逐页替换，不再出现「页面有、接口无」。
> 基线：切片 1（设备数据 → 核算 → 报告）已完成并跑通，P1–P4 已全部交付。

## 1. 领域划分

| 域 | 覆盖的原型页面 | 核心表 |
|---|---|---|
| `org` 组织 | 企业信息、能源管理情况 | `org`（含档案字段）、`energy_mgmt` |
| `device` 设备 | 设施管理、设备管理、设备数据 | `facility`、`device`、`device_reading` |
| `factor` 因子 | 排放因子（新增能力） | `emission_factor` |
| `product` 主数据 | 产品管理、供应链碳管理 | `product`、`supplier` |
| `asset` 碳资产 | 配额资产、信用资产、交易管理 | `carbon_asset`、`carbon_trade`、`compliance_period` |
| `footprint` 足迹 | 产品碳足迹 | `product_footprint`、`footprint_stage` |
| `accounting` 核算 | 碳排放核算 | `accounting_period`、`accounting_line` |
| `report` 报告 | 碳排放报告、能源报告、足迹报告、第三方报告 | `report`、`third_party_report` |
| `alert` 预警 | 预警设置、事件预警、预警记录 | `alert_rule`、`alert_event`、`alert_record` |
| `evidence` 凭证 | 原始凭证 | `evidence`、`file_object` |
| `output` 产值 | 产品产值、生产总值 | `production_value`、`total_value` |
| `retrofit` 节能 | 节能改造 | `retrofit` |
| `analysis` 分析 | 6 个能碳分析页 | 无表，只读聚合 |

共 **26 张业务表**（含切片 1 的 10 张；另加 Flyway 历史表，物理表 27 张）。

## 2. 分层与包结构

```
com.carbonbal
├── common/            信封、错误码、异常、分页、期间守卫、字典
├── auth/              登录、JWT、RBAC、用户
├── audit/             审计（只追加）
├── file/              文件元数据与本地存储
├── <domain>/
│   ├── entity/        MyBatis-Plus 实体
│   ├── mapper/        BaseMapper + 少量聚合 SQL
│   ├── service/       业务逻辑（事务边界）
│   ├── controller/    HTTP 入口（参数校验、权限）
│   └── dto/           record 形式的请求/响应
└── analysis/          跨域只读聚合
```

规则（沿用切片 1）：

1. Controller 不写业务，只做参数校验 + 调 Service + 包信封。
2. 事务边界在 Service；跨表写必须同事务。
3. 所有写操作调 `AuditService.record`，改前/改后各一份。
4. 历史数据写入口先过 `PeriodGuard`（核算期锁定即拒绝）。
5. 列表统一 `page/size/keyword`，返回 `{ total, rows }`，最大 size 200。

## 3. 通用字段约定

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGINT AUTO_INCREMENT | 主键 |
| `org_id` | BIGINT | 企业隔离，当前固定 1，多租户预留 |
| `version` | INT | 乐观锁，更新 `WHERE id=? AND version=?` |
| `disabled` | TINYINT(1) | 软删除；列表默认过滤 |
| `created_at` / `updated_at` | DATETIME | 创建/更新时间 |
| `created_by` / `updated_by` | VARCHAR(64) | 操作人（审计之外的行级留痕） |

> 不物理删除业务数据，`disabled=1` 代替；管理员可恢复。

## 4. 接口规范

- 路径：`/api/<resource>`，复数、小写、连字符（`/api/carbon-assets`）。
- 方法：GET 列表/详情、POST 创建、PUT 全量更新、DELETE 软删（PATCH 已下线，更新须提交完整主体）。
- 信封：成功 `{ status: 0, body: {...} }`；失败 `{ status: "ERROR_CODE", body: { message, detail } }`；HTTP 状态由错误码映射。
- 列表响应：`{ total, rows, page, size }`。
- 错误码：字符串业务码，见 `docs/error-codes.md`；只增不改，前端按 `status` 分支，不解析中文文案。
- 鉴权顺序：401（无会话）→ 403（角色/归属）→ 400（参数）→ 409（业务冲突）。
- 分页参数：`page` 从 1 开始，`size` 默认 10、上限 200。
- 幂等：POST 创建类接口接受 `Idempotency-Key` 请求头（≤128 字符，24h），同键同请求体重放首次结果（`Idempotent-Replay: true`），同键不同请求体 409；设备读数另保留请求体字段 `clientRequestId`。

## 5. 权限矩阵

角色：`platform_admin`、租户 `admin`、`filler`、`reviewer`、`approver`、`viewer`。
完整矩阵（能力 × 角色 × 对象归属 × 二次确认）见 `docs/permissions.md`，职责分离规则见同文 §2。

实现：`@PreAuthorize` 方法级注解 + Service 内 `org_id` 二次校验（防越权直接调 Service）；前端按 `CarbonAPI.isAdmin/canWrite/canReview/canApprove` 隐藏入口。

## 6. 文件与导出

- `file_object` 表存元数据（原名、大小、sha256、存储键、业务归属），内容落本地磁盘 `${app.file.root}`，生产可换 OSS 而接口不变。
- 上传：`POST /api/files`（multipart），返回 `fileId`；业务表存 `file_id`。
- 下载：`GET /api/files/{id}/download`，带 `Content-Disposition` 与 sha256 头。
- 导出：`GET /api/export?resource=<台账>&from=&to=&keyword=`，统一 CSV 入口（12 类台账，UTF-8 BOM）；
  报告打包：`GET /api/reports/bundle?type=&period=`，返回 ZIP。
- 前端：`GET /api/bootstrap?year=` 只返回字典、KPI、首页聚合与主数据（产品/供应商/资产/设施/设备/预警）；9 类流水台账不再进快照，改由 `/api/*` 服务端分页。
- 凭证/第三方报告从「文件名」升级为「file_id 列表」，历史数据兼容。

## 7. 分析聚合

分析页不建表，按需聚合（能碳分析 6 页 + 碳足迹/供应链 2 页 + 首页）：

| 页面 | 接口 | 聚合来源 |
|---|---|---|
| 能耗查询 | `GET /api/analysis/energy-consumption` | `device_reading` + `EnergyMath` 折标 |
| 碳排放查询 | `GET /api/analysis/carbon-emission` | 读数 × 三级因子，拆 scope1/2 |
| 能效对标 | `GET /api/analysis/energy-efficiency` | `device_reading` + `production_value` + `product.l1–l3` |
| 能流分析（桑基） | `GET /api/analysis/energy-flow` | 读数按品种 → 设备两层流向 |
| 策略推荐 | `GET /api/analysis/strategy` | 上述结果的规则引擎（阈值 + 排序） |
| 碳配额核算 | `GET /api/analysis/quota` | `carbon_asset` + 年度排放 |
| 碳足迹分析 | `GET /api/analysis/footprint` | `product_footprint` 阶段拆分 |
| 供应链分析 | `GET /api/analysis/supply-chain` | `supplier` 足迹排序；`supplier` 参数按「该供应商 + 全部下级」取数 |
| 首页 | `GET /api/overview` | 上述指标 + 待办 + 预警 |

约定：聚合只在一个 `AnalysisService` 实现，前端只展示；返回结构固定 `{ kpis, series, table, sankey? }`。
折标煤系数集中在 `common/EnergyMath`（电力 0.1229 tce/MWh、天然气 11.0 tce/万Nm3、柴油 1.4571 tce/t、汽油 1.4714 tce/t、蒸汽 0.0341 tce/GJ）。

## 8. 非功能

| 项 | 约定 |
|---|---|
| 分页 | 一律数据库分页，禁止全量查后内存切 |
| 索引 | 列表筛选字段（org_id + 业务时间 + 状态）建联合索引 |
| 并发 | 乐观锁；交易数量扣减用 `UPDATE ... WHERE qty >= ?` 原子扣减 |
| 性能 | 列表 P95 < 300ms（单表 10 万行内）；聚合走索引 |
| 事务 | 单次请求内跨表写同事务；不做分布式事务 |
| 日志 | 未捕获异常记录 method + uri + 堆栈；业务异常不打堆栈 |
| 时间 | 全部 `DATETIME`，服务器时区 Asia/Shanghai |

## 9. 交付与部署

- 单 jar，前端产物进 `static/`；`java -jar` 即用。
- Flyway 迁移按域编号：`V10__product.sql`、`V20__asset.sql`…，只增不改。
- 配置：`dev`（H2 MySQL 模式）+ `prod`（MySQL 8）。
- 门禁：`mvn test`（域内断言）+ `tools/e2e.sh`（端到端）+ 架构断言（表/接口清单一致性）。

## 10. 分域交付（P1–P4 全部完成）

| 批 | 域 | 表 | 接口 | 依赖 |
|---|---|---|---|---|
| 切片 1 | auth、device、factor、accounting、audit | 10 | 17 | — |
| P1 | product、supplier | 2 | 8 | 无 |
| P1 | carbon_asset、carbon_trade | 2 | 7 | product（可选） |
| P1 | product_footprint | 1 | 4 | product、factor |
| P1 | file_object（通用文件） | 1 | 4 | 无 |
| P2 | alert（规则/事件/记录） | 3 | 12 | device、accounting |
| P2 | energy_mgmt、retrofit | 2 | 6 | org |
| P2 | facility（设施树） | 1 | 5 | 无 |
| P3 | output（产值/总值） | 2 | 10 | product |
| P3 | evidence、third_party_report | 2 | 10 | file |
| P4 | analysis 8 页 + 首页 | 0 | 9 | 全部 |
| P4 | report 多类型扩展 | 重建 1 表 | 5（含打包） | accounting、footprint |
| — | 企业信息档案 | 0（扩列） | 2 | org |
| — | 台账 CSV 导出 | 0 | 1 | 全部列表 |

每批交付：迁移脚本 + 实体/服务/接口 + 域内测试 + e2e 追加断言 + 文档更新。

---

## 11. 实施状态（2026-11-25）

| 批 | 域 | 表 | 接口 | 状态 |
|---|---|---|---|---|
| 切片 1 | auth / device / factor / accounting / audit | 10 | 17 | ✅ |
| P1 | product / supplier / asset / trade / footprint / file | 9 | 23 | ✅ |
| P2 | alert_rule / alert_event / alert_record / energy_mgmt / retrofit | 5 | 18 | ✅ |
| P2 | facility（设施树） | 1 | 5 | ✅ |
| P3 | production_value / total_value | 2 | 10 | ✅ |
| P3 | evidence / third_party_report | 2 | 10 | ✅ |
| P4 | analysis 8 页 + 首页聚合 | 0 | 9 | ✅ |
| P4 | report 多类型（含切片 1 的 3 个 + 打包） | 重建 1 | 5 | ✅ |
| — | 企业信息（org 档案 + logo/执照） | 扩 18 列 | 2 | ✅ |
| — | 台账 CSV 导出 | 0 | 1 | ✅ |

当前统计口径已收敛到 §15.7 一处（避免同文档两处数字自相矛盾），本节不再重复。

### 11.1 已落地的横切能力

- 多企业隔离：`CurrentUser.orgId()` 来自 JWT，各 Service 按 `org_id` 过滤；跨企业访问返回 403
  （注：租户行级拦截器方案已设计并在本地验证过，按 2026-11-25 决策回退，待用户体系一并实施）
- 分页：`PageResult{total,rows,page,size}`，size 上限 200，数据库分页
- 乐观锁：所有可变实体带 `version`，冲突返回 409 + 当前版本
- 软删除：`disabled=1`，列表默认过滤，管理员可删
- 审计：所有写操作记录改前/改后 JSON
- 文件：`file_object` 元数据 + 本地磁盘，sha256 校验，下载带指纹头
- 交易：售出用 `UPDATE ... WHERE qty >= ?` 原子扣减，超卖 409；删除反向冲销
- 期间守卫：`PeriodGuard` 按 `org_id + period` 判断锁定，产值写入与读数修改同样受限
- 登录锁定：同一「用户名 + IP」连续失败 5 次锁 15 分钟，返回 429；成功登录清零（单实例内存实现）
- 导出：`/api/export` 覆盖 12 类台账，CSV 带 BOM 与 RFC 4180 转义；报告按类型/期间打 ZIP
- 控制台：`/console.html` 共 9 个视图（总览/设备/核算/产值/核查/设施/分析/企业信息/审计）
- 前端：`/app/` 直接复用原型 31 个路由，登录后注入快照；`/` 跳转到 `/app/`

### 11.2 P3/P4 落地要点

**产值**：`production_value(period, product_id, product_name 快照, output_value, qty …)` 与
`total_value(period, name, output_value, qty)`。因软删与数据库唯一索引冲突，唯一性在服务层按
`disabled=0` 校验（同月同产品 / 同月同名各一条），删除后可重建；写入前过 `PeriodGuard`。

**凭证/第三方报告**：凭证多附件通过 `file_object(biz_type='evidence', biz_id=凭证ID)` 绑定；
第三方报告单文件存 `file_id`。更新时 `fileIds=null` 表示不动附件、`[]` 表示全部解绑；
删除时自动解绑，文件本身保留可复用。有效期拆 `valid_from/valid_to`，结束早于开始直接 400。

**分析**：`AnalysisService` 统一返回 `{kpis, series, table, sankey?}`；折标系数集中在 `EnergyMath`；
排放按「读数 × 当月经三级优先级选出的已审核因子」实时计算，缺因子时该条排放记 0 并在 KPI 口径中体现，
不阻断整个分析页。桑基图为「总能耗 → 能源品种 → 设备」三层，流带值与节点值一致。
碳足迹分析取最近一条足迹拆三阶段；供应链分析按足迹倒序输出供应商排名。

**设施**：`facility` 自关联 `parent_id`，层级由父级 `tree_level + 1` 推导（不接受客户端传层级）；
编码同企业内唯一（`disabled=0`）；移动节点时校验不能挂到自身或下级，并递归重算子树层级；
存在下级时删除返回 409。

**读数精度**：`device_reading.rate` 由 `NUMERIC(12,4)` 扩到 `NUMERIC(12,6)`（V96），
解决柴油 0.00085 t/L 被四舍五入成 0.0009 的问题；只回填 `client_request_id LIKE 'seed-%'` 的种子，
不覆盖人工补录数据。

---

## 12. 前端架构：原型接管（2026-11-25）

**结论**：31 个路由、4,000+ 行 UI 直接复用，前端不再逐页重画；控制台降为运维/调试台。

```
浏览器
 ├─ /            → 跳转 /app/
 ├─ /app/        原型 index + assets/api-client.js   （业务前端）
 └─ /console.html  9 视图控制台                      （运维前端）
        ↓ 同一套 /api
Spring Boot 单 jar + MySQL
```

**启动顺序**（关键）：`index.html` 先建立空数据结构 `window.DB`（仅键与空数组，无假数据）→ `api-client.js` → `menu/framework/topbar`
→ `authenticate()`（登录 + `GET /api/bootstrap`）→ 动态加载 `pages-core/pages-generic/pages-system/...`
→ `initApp()` 渲染。页面脚本在快照之后加载，列表页通过 `DB[key]` 的数组身份变化完成二次同步。

**源码与产物**：`carbonbal-web/` 是唯一源码目录，`backend/src/main/resources/static/app` 由 `platform/tools/sync-frontend.sh` 生成；`--check` 可检测漂移。

**读写边界**：

| 能力 | 状态 |
|---|---|
| 读：首页/字典/主数据 | `/api/bootstrap` 快照（26KB 量级，不含流水台账） |
| 读：14 个台账 + 预警记录 | `/api/*?page=&size=` 服务端分页，前端分页器吃后端 `total`；筛选与关键词转查询参数；预警 KPI 走 `/api/alert-records/stats` |
| 角色化 UI | viewer/reviewer/approver 隐藏写按钮；filler 隐藏删除；批量删除仅管理员 |
| 写：14 个列表页 | 产品、产值、总值、配额资产、信用资产、交易、凭证、第三方报告、设施、设备读数、事件字典、改造项目、能源管理情况、供应链 |
| 写：核算闭环 | 新建（选月即算）、重算、提交、**复核**、**批准**、锁定、解锁（填原因）、明细按 `/accounting-periods/{period}`；操作按钮按角色渲染（填报/复核/批准/管理员） |
| 写：报告 | 生成 carbon/energy/footprint、下载 PDF、删除、按类型/期间查询；历史列表内嵌在页面 |
| 写：企业信息 | 管理员 PUT `/api/org`（乐观锁，全量提交），其余角色只读 |
| 写：预警 | 规则增删改与启停；记录已读/处理/全部已读/批量删除 |
| 读：8 个分析页 | 改调 `/api/analysis/*`，KPI/趋势/构成/明细全部来自后端聚合，不再前端自算 |
| 导入 | 设备读数 CSV（模板前端生成、`POST /device-readings/import`）；其余页面不显示导入入口 |
| 写：设备管理 | 新建 / 编辑 / 禁用·启用 / 删除（有读数时拒绝）/ CSV 导入，字段含设施、类型、级别；导出走 `/api/export?resource=devices` |
| 写：年度配额 | `/api/quota-years` 增改删；首页与配额页的配额量改从 `quota_year` 读取 |
| 写：产品碳足迹 | 新建 / 编辑 / 删除 / 批量删除，接 `/api/footprints` |
| 批量导入 | 产品、产值、碳资产、供应商、设施走 `POST /api/import?resource=`；模板从 `/api/import/template` 生成 |
| 只读兜底 | 无（所有列表页均有写接口） |
| 报告打包 | 报告历史右上角「打包 ZIP」接 `/api/reports/bundle` |
| 导出 | 12 类台账走 `/api/export`；报告 ZIP 走 `/api/reports/bundle` |
| 源码与产物 | `bash platform/tools/sync-frontend.sh` 同步；`--check` 检测漂移，禁止直接改 `static/app` |

**权限**：admin 全量；filler 可写；viewer 隐藏新建/编辑/删除并由后端二次校验（403）。
登录失败 5 次锁 15 分钟，与 API 侧一致。

**报告**：`report` 表重建以支持 `period_id` 可空（足迹报告无核算期）、`org_id` 隔离与
`report_type/ref_id/period_label/disabled`；迁移保留原主键与全部历史碳报告。三类 PDF 均带
`[MACHINE]` ASCII 摘要（`type=… period=… tce=…` / `type=footprint … footprint=…`），供自动化校验。
能源报告产值优先取产品产值合计，缺失回落生产总值。


---

## 13. 定时任务与运维（2026-11-25）

### 13.1 定时任务

| 任务 | 默认 cron | 作用 | 幂等策略 |
|---|---|---|---|
| `scan-alerts` | 每小时 | 按启用规则比当月能耗/排放，超阈值写 `alert_record` | 同规则当月已存在记录则跳过 |
| `quota-reminder` | 每月 1 日 08:30 | 年度配额使用率 ≥90% 生成履约提醒 | 每月只提醒一次 |
| `monthly-reports` | 每月 1 日 02:40 | 为上月生成碳报告与能源报告 | 同类型同期间已有报告则跳过 |

- 开关：`app.jobs.enabled`（dev 默认 false，prod 默认 true，可用 `JOBS_ENABLED` 覆盖）
- 手动触发：`POST /api/jobs/{name}/run`（仅 admin，作用于当前企业），`GET /api/jobs` 列出任务名
- 租户上下文：任务通过 `SystemAuth.runAs(orgId, "system", …)` 设置租户，逐个企业执行，单企业失败不影响其他企业

### 13.2 运维能力

| 能力 | 入口 | 说明 |
|---|---|---|
| 健康检查 | `GET /api/health` | 免登录，返回 `status` 与 `db`；Dockerfile 的 HEALTHCHECK 与 nginx 探针使用 |
| 冒烟 | `bash tools/smoke.sh` | 健康检查 + 登录 + 6 个只读接口 + 前端入口 |
| 备份 | `bash tools/backup.sh` | mysqldump 全量 + 上传文件打包，按天保留（默认 14 天） |
| 容器化 | `deploy/Dockerfile`、`deploy/docker-compose.yml` | 多阶段构建；MySQL 独立卷；JWT_SECRET 必填 |
| 反代 | `deploy/nginx.conf` | HTTPS 终止、`client_max_body_size 12m`、健康检查免日志 |
| 密钥外置 | `JWT_SECRET` / `JWT_TTL_HOURS` | 默认值仅用于本地；生产必须注入 |
| 日志 | `LOG_FILE`（prod 默认 `./logs/carbonbal.log`） | 单文件 20MB 滚动，保留 14 份 |

---

## 14. 用户体系与多租户（v100–v102，2026-11-25）

### 14.1 模型

```
app_user（账号：手机号全局唯一，可无密码）
   └─ user_tenant（成员关系：org_id + role + status + user_type + role_id）
          └─ org（租户：status / plan / seat_limit / expire_at / parent_org_id / org_level）
```

- **一人一账号，可属多个租户**；角色挂在成员关系上，同一人在 A 公司是管理员、在 B 公司是填报表。
- 账号级状态（`app_user.status`）与成员级状态（`user_tenant.status`）严格分开：
  管理员在「账号管理」停用的是成员关系，不影响该账号在其他企业；只有风控才改账号状态。
- 成员不再区分内外部，也不再单独设置数据范围与到期日（V109 移除）；成员有效期只受企业授权期约束。

### 14.2 角色与职责分离

| 成员类型 / 角色 | 能力 |
|---|---|
| 租户管理员（`user_type=tenant_admin`） | 成员管理、企业信息、全部业务读写、复核/批准/锁定 |
| 普通用户（`user_type=user`，角色如内置「运营」） | 角色勾选的业务页面；可填报、发起核算、提交，并可执行复核/批准动作 |
| 外部协作者 | 仅「我的授权」范围内的报告/数据 |

演示账号的 4 个普通用户（张三、李四、王五、赵六）角色一致，均为「运营」，不预设填报/复核/批准岗位。

核算状态机：`draft → calculated → submitted → reviewed → approved → locked`（`unlock` 回 draft 并写 `period_unlock`）。五列表与账号/成员/租户状态机见 `docs/state-machines.md`。
职责分离在服务层强制：编制人 ≠ 复核人、复核人 ≠ 批准人，违规返回 409。

### 14.3 登录与会话

| 项 | 设计 |
|---|---|
| 账号标识 | 手机号（`app_user.phone` 唯一）；`username` 列保留一版用于回滚与脚本兼容 |
| 密码登录 | `POST /api/auth/login {phone, password, captchaId, captchaCode}`；图形验证码必填（`app.auth.captcha.login-enabled` 可关）；连续 5 次失败锁 15 分钟（落库 `failed_count` / `locked_until`） |
| 短信登录 | `POST /api/auth/sms/send`（先过图形码）→ `POST /api/auth/sms/login {phone, code}`；6 位、5 分钟、错 5 次作废；同号 60 秒一条、每天 10 条，同 IP 每小时 20 条 |
| 图形码 | `GET /api/auth/captcha` 返回 SVG base64；2 分钟、一次性、最多试 3 次 |
| 多租户选择 | 登录返回 `tempToken + tenants`，`POST /api/auth/tenant/select` 换正式令牌；单租户直接发令牌 |
| 切换企业 | `POST /api/auth/switch-tenant {orgId}`（需登录），前端清空快照后重拉 `/api/bootstrap` |
| 令牌 | JWT：`sub=手机号`、`uid`、`phone`、`org`、`role`、`tv`（token_version） |
| 令牌存放 | 优先 HttpOnly `cb_token` Cookie（`SameSite=Lax`，prod 加 `Secure`），回退 `Authorization: Bearer`（脚本/e2e） |
| CSRF | double-submit：写请求需带 `X-CSRF-Token`，值等于非 HttpOnly 的 `cb_csrf`；用 Bearer 的客户端放行 |
| 失效 | 改密、重置、停用、改角色、移出 → `token_version+1`，旧令牌立即 401；账号状态/成员状态缓存 30 秒 |
| 强制改密 | 新建账号与重置密码后 `must_change_password=true`，除改密/查询自身外全部 403 `MUST_CHANGE_PASSWORD`；临时密码 7 天有效 |

### 14.4 密码策略

≥12 位，含大写、小写、数字、符号四类；90 天过期；不得与最近 3 次重复（`password_history`）。
bcrypt cost 10（`password_algo` 字段支持后续升级）。管理员重置生成一次性临时密码，只在响应中出现一次。

### 14.5 租户隔离

`OrgTenantHandler` + `TenantLineInnerInterceptor`（在分页拦截器之前）对所有业务表自动追加
`org_id = 当前企业` 并在插入时写入。豁免表：`org`、`app_user`、`user_tenant`（登录阶段需跨租户查询）、
`emission_factor`（国家级因子 `org_id` 为空）、`sms_code`、`captcha`、`login_log`、`password_history`、`phone_history`。
审计、核算明细、解锁记录补齐 `org_id` 并按租户过滤。

### 14.6 审计

`audit_log` 增 `org_id`、`actor_id`、`action_label`（中文动作名），按租户查询。
登录流水独立在 `login_log`（成功/失败、渠道、IP、UA），保留 90 天，`GET /api/users/{id}/login-logs` 可查。

### 14.7 接口

| 方法 | 路径 | 权限 |
|---|---|---|
| GET | `/api/auth/captcha` | 匿名 |
| POST | `/api/auth/sms/send`、`/sms/login` | 匿名 |
| POST | `/api/auth/login`、`/auth/tenant/select` | 匿名 |
| POST | `/api/auth/switch-tenant`、`/password/change`、`/phone/rebind`、`/logout` | 登录 |
| GET | `/api/auth/me`、`/password-policy` | 登录 |
| GET/POST | `/api/users` | admin |
| PUT/DELETE | `/api/users/{membershipId}` | admin |
| POST | `/api/users/{id}/disable`、`/enable`、`/reset-password`、`/unlock`、`/handover` | admin |
| POST | `/api/users/import`、GET `/api/users/import-template`、GET `/api/users/export` | admin |
| GET | `/api/users/{id}/login-logs` | admin |
| POST | `/api/accounting-periods/{period}/review` | admin / reviewer |
| POST | `/api/accounting-periods/{period}/approve` | admin / approver |
| POST | `/api/accounting-periods/{period}/lock` | admin / approver |

### 14.8 数据迁移

`V100__identity_tenant.sql`：租户/账号扩列、6 张新表、审计与核算子表补 `org_id`、核算四岗字段；
存量账号回填手机号（`admin/filler/viewer → 13800000001~3`）并生成成员关系。
`V101` 允许 `username` 为空；`V102` 允许 `app_user.role/org_id` 为空（改由成员关系承载）。

演示账号：`13800000001/Admin@2026!Pass`、`13800000002/Test@2026!Pass`、`13800000004/Test@2026!Pass`、
`13800000005/Test@2026!Pass`、`13800000003/Test@2026!Pass`。

---

## 15. 平台管理与安全加固（v103 起，2026-11-25 更新）

### 15.1 平台管理端（超级管理员）

- 新增 `app_user.platform_admin`：平台账号不属于任何企业（`orgId=0`），角色固定 `platform_admin`。
- 登录走单独分支，不产生成员关系；`JwtAuthFilter` 对 `orgId=0 + platform_admin` 跳过成员校验。
- 接口（`/api/platform/**`，`@PreAuthorize("hasRole('PLATFORM_ADMIN')")`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/platform/tenants` | 租户分页列表：授权期、剩余天数、席位、成员、管理员；支持关键词/状态/30 日内到期 |
| GET | `/api/platform/tenants/{id}` | 租户详情（含设备与报告数） |
| POST | `/api/platform/tenants` | 开通租户：建 org + 管理员账号 + 成员关系 + 默认厂区，返回一次性密码 |
| PUT | `/api/platform/tenants/{id}` | 企业名、套餐、席位、授权起止、状态 |
| POST | `/api/platform/tenants/{id}/renew` | 续期：顺延 N 个月或指定到期日 |
| POST | `/api/platform/tenants/{id}/suspend` \| `/resume` | 停用 / 恢复 |
| GET | `/api/platform/stats` | 租户、正常/未生效/到期、30 日内到期、席位使用、设备与报告总量 |
| GET | `/api/platform/usage` | 按租户的成员/设备/报告用量 |

- 授权期：`org.license_start_at`（开始日期）+ `org.expire_at`（到期日 23:59:59），开通时必填、续期可改。
- 租户状态语义：`pending` 未生效（403 `TENANT_PENDING`）、`active` 正常、`suspended` 停用（423 `TENANT_SUSPENDED`）、`expired` 到期（403 `TENANT_EXPIRED`，读接口同样拒绝，无宽限期）；历史 `disabled` 归入 `suspended`。
- 到期判定双保险：`TenantLicenseJob` 每天 00:05 落库，同时列表与登录按 `expire_at` 实时判断，不依赖任务执行。
- 状态变更会立即失效该企业所有成员的缓存，已登录会话在下次请求即被拒绝。
- 超级管理员进入租户：`POST /api/auth/enter-tenant` 签发 `imp=true` 令牌（租户内 `role=admin`），不受租户状态、成员关系、职责分离限制；`POST /api/auth/exit-tenant` 回平台。代管期间的写操作按该租户 `org_id` 记审计。

### 15.2 成员模型（V109 起）

- 成员关系只保留：`user_type`（tenant_admin / user）、`role_id`、`status`、工号/职位。
- 成员类型（内部/外部）、数据范围（ORG/SELF）、成员级授权到期三项已移除：
  所有成员按内部员工处理，数据按企业范围可见，成员有效期只受企业授权期约束。
- 原 `external_grant` 表、`/api/external/**` 接口与外部门户页面已下线；
  历史外部账号在迁移时统一停用，保留账号与审计记录。

### 15.3 集团—工厂汇总

`org.parent_org_id` + `org_level`，`GET /api/group/summary?year=` 用 JdbcTemplate 跨企业聚合
（排放、能耗、设备、成员），自动带出同集团的其他工厂。前端「企业管理 → 集团汇总」。

### 15.4 安全加固

| 项 | 实现 |
|---|---|
| JWT 密钥 | 启动强校验：非空、≥32 位、不含开发默认值；`deploy/gen-secret.sh` 生成 64 位随机值 |
| 短信验证码 | 默认随机 6 位；`SMS_EXPOSE_CODE` 仅演示环境可回显；生产必须接真实通道 |
| 匿名接口白名单 | 登录/发码/图形码/选租户/退出/健康检查跳过认证过滤器，避免过期令牌导致无法重新登录 |
| 静态资源 | 认证与 CSRF 过滤器只作用于 `/api/**` |
| CSRF | double-submit，`X-CSRF-Token` 与非 HttpOnly 的 `cb_csrf` 比对；Bearer 客户端放行 |
| 外部账号 | 路径白名单 + 对象级授权双重校验 |
| 换绑手机号 | 旧号验证码 + 图形码 + 新号验证码，成功后 `token_version+1` 全端下线 |

### 15.6 运维

- `deploy/gen-selfsigned.sh` 自签证书、`deploy/nginx-https.conf` HTTPS 反代（HSTS、TLS1.2+、上传 12m）。
- `tools/backup.sh`：`--single-transaction --routines --triggers --no-tablespaces --default-character-set=utf8mb4 --hex-blob`。
- `tools/restore.sh`：二次确认后恢复，带 `utf8mb4` 客户端字符集。
- `tools/monitor.sh`：健康检查 + 关键接口探针，异常退出码供 cron/告警使用。
- 备份恢复已演练：9.0 MB 备份 → 恢复临时库 → 35 张表 / 23 账号 / 91 报告 / 24 读数，与源库一致。
- 部署与上线清单见 `docs/deploy.md`，用户操作见 `docs/user-manual.md`。

### 15.7 当前口径

**39 张业务表、183 个 API、135 条断言、102 项端到端、33 项数据门禁、14 项性能预算**。
