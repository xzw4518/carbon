# 平台交付报告 · 第五批（功能补口与生产化）

> 日期：2026-11-25　范围：把上一轮列出的 9 项缺口逐项落地，能上的都上，不能上的写清原因。
> 前置：`report-batch4.md`（原型接管收口）、`REQ-20261125-002`。

## 1. 交付清单

| # | 缺口 | 结果 |
|---|---|---|
| 1 | 产品碳足迹页写不进去 | ✅ 新建/编辑/删除/批量删除接 `/api/footprints` |
| 2 | 设备管理无 CRUD | ✅ 后端 `POST/PATCH/DELETE /api/devices` + 禁用/启用 + CSV 导入 + 导出；`V98` 扩列（设施、类型、级别、备注、乐观锁）；有读数拒绝删除（409 + 条数） |
| 3 | 年度配额无接口 | ✅ `V97__quota_year.sql` + `/api/quota-years` 增改删；首页与配额页改读该表（快照 `quota.source=quota_year`） |
| 4 | 报告打包前端未接 | ✅ 报告历史右上角「打包 ZIP」→ `/api/reports/bundle?type=&period=` |
| 5 | 除读数外的批量导入 | ✅ `POST /api/import?resource=` 覆盖产品、产值、碳资产、供应商、设施；模板 `/api/import/template`；逐行独立提交，返回失败行号与原因 |
| 6 | 服务端分页 | ⚠️ 未做（见 §4）；改以 HTTP 压缩 + 快照分页兜底 |
| 7 | 定时任务 | ✅ `scan-alerts`（每小时）、`quota-reminder`（每月 1 日）、`monthly-reports`（每月 1 日）；`app.jobs.enabled` 控制；`POST /api/jobs/{name}/run` 手动触发（admin） |
| 8 | SSO / 数据权限到人 | ⚠️ 未做（见 §4） |
| 9 | 部署加固 | ✅ `/api/health`、`tools/smoke.sh`、`tools/backup.sh`、`deploy/Dockerfile`、`docker-compose.yml`、`nginx.conf`、JWT 密钥与日志路径外置、日志滚动 |

## 2. 关键实现

**多租户下的定时任务**：新增 `SystemAuth.runAs(orgId, "system", …)`，用 `Map` 形式的 details 承载租户，
`CurrentUser.orgId()` 同步支持 `Map`；任务按 `org` 表逐企业执行，单企业异常只记日志不中断。

**导入的部分成功语义**：外层不加事务，逐行调用既有 Service（各自事务）。
一次导入里失败行不影响成功行；返回 `{success, failed, errors:[{line, reason}]}`。
踩过的坑：外层加 `@Transactional` 时，内层 409 会把整个事务标记 rollback-only，导致全批回滚并返回 500。

**设备删除保护**：设备下存在读数时返回 409 并带 `detail.readings`，引导改用「禁用」；
禁用设备仍参与历史数据，但不参与后续核算与预警。

**配额口径**：`quota_year` 由 `V97` 从历史 `carbon_asset` 汇总初始化，之后以表为准；
快照返回 `source` 字段标明来源，前端不再自行推算下年度配额。

## 3. 验收

```
mvn test         PASS 51/51（新增 DeviceQuotaJobTest 5 条）
e2e.sh           PASS 64/64（新增设备 CRUD、配额、导入、任务、健康检查 14 项）
smoke.sh         PASS 9/9
Flyway           v1 → v98（V97 配额年度、V98 设备扩展）
表 / 接口         27 张业务表（物理 28）/ 116 个 API
```

界面实测（无头 Chrome，admin）：设备页 6 台真实设备、可新建（设施下拉取真实设施）、
配额页显示 2026 年度配额、足迹页有增删入口、报告页有「打包 ZIP」、产品页有「导入」而交易页没有。

## 4. 未完成项与原因

| 项 | 状态 | 原因与建议 |
|---|---|---|
| 服务端分页 | 未做 | 列表仍是 `/api/bootstrap` 全量快照 + 前端分页。已开 HTTP 压缩（400KB → 约 60KB），万行以内可接受；上万行需逐页改 `listPage` 为服务端分页，接口侧已全部支持 `page/size` |
| SSO | 未做 | 需要确定 IdP（企业微信/钉钉/Azure AD/Keycloak）与账号映射规则，属于对接决策而非纯开发；当前保留本地账号 + JWT，接入点集中在 `AuthController` 与 `JwtAuthFilter` |
| 数据权限到人 | 未做 | 需要先定模型（谁能看谁的数据：本人/本部门/本企业）。当前为「多企业隔离 + RBAC 三角色」，已满足租户隔离；到人需要组织架构与审批口径 |
| 登录锁定多实例 | 未做 | 现为单实例内存计数；多副本部署需换 Redis（`LoginAttemptService` 已收敛为单类，替换成本低） |

## 5. 证据

- `target/surefire-reports/`（51 条）
- `target/e2e-v9.out`（64 项）
- `target/carbonbal.jar`（38.8MB）
- `docs/architecture.md` §12–§13、`README.md` §8.5、需求卡 `REQ-20261125-003`

## 6. 回滚

```bash
# 代码：回退 jar/jar 内静态资源
mvn -DskipTests package
# 数据库：V97/V98 为新增表与新增可空列，回滚只需回退应用；如需清表：
#   DROP TABLE quota_year;  ALTER TABLE device DROP COLUMN ...;
```
