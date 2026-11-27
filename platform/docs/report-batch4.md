# 平台交付报告 · 第四批（原型接管收口）

> 日期：2026-11-25　范围：把 `/app/` 原型从「能看」做到「能用」，补齐写链路、核算与报告闭环、分析口径。
> 前置：`report-batch3.md`（后端 26 表 / 100 接口）、`REQ-20261125-002`（原型接管）。

## 1. 本轮交付

| 模块 | 变更 | 结果 |
|---|---|---|
| 快照契约 | `BootstrapService` 为 products / suppliers / assets / ccers / trades / retrofits / energyMgmt / deviceData / alertRules / alertEvents / accounting 补 `dbId`、`version`；核算补 `rawStatus`、`calcVersion`；企业信息补 `version`、`logoFileId`、`licenseFileId`；新增 `devices` 列表 | 前端可按主键与乐观锁回写 |
| 列表写映射 | `api-client.js` 从 5 类扩到 14 类：产品、产值、总值、配额资产、信用资产、交易、凭证、第三方报告、设施、设备读数、事件字典、改造项目、能源管理情况、供应链 | 保存/删除均落库，成功后重取快照重绘 |
| 表单选项 | `f.options` 支持函数，打开弹窗时按最新 `DB` 解析；交易页补「购买／出售方」、资产改下拉、日期可编辑；设备数据设备名取真实设备 | 不再出现 mock 名称与真实数据混用 |
| 核算闭环 | 新建（选月份即计算）、重算、提交、锁定、解锁（必填原因）；状态列展示 draft/calculated/submitted/locked；明细弹窗读 `/accounting-periods/{period}` 真实排放行 | 前端可完成整条期间状态机 |
| 报告链路 | 生成 carbon / energy / footprint → 下载 PDF（sha256 指纹提示）→ 删除；报告查询页内嵌历史列表；页面 KPI/趋势/构成改读 `/api/analysis/*` | 报告页不再出现写死的演示数字 |
| 企业信息 | 管理员可编辑（PATCH `/api/org`，带乐观锁），其他角色只读 | 只读页变为可维护 |
| 预警 | 规则：新建、改阈值/冷却、启停、删除、批量删除；记录：标记已读、处理、全部已读、批量删除 | 全部走 `/api/alert-*` |
| 分析八页 | `analysisPage` 改为后端驱动：KPI 卡片、月度趋势、构成条图、明细表（能流为三层桑基）均由 `{kpis,series,table,sankey}` 渲染 | 8 页数字与后端口径一致 |
| 只读兜底 | `lockWrites` 由“白名单可写”改为“黑名单只读”，仅设备管理页（后端无设备 CRUD）隐藏写按钮 | 顺带修复「生成报告」按钮被误删 |
| 导入 | 设备读数 CSV 导入接 `POST /device-readings/import`，模板前端生成（BOM + 表头 + 示例行）；其他页隐藏导入入口 | 不再有“模拟导入成功” |
| 适配层缺陷 | `api-client` 内 `toast` 未定义导致保存后抛错（数据已落库但提示异常） | 补 `UI.toast` 兜底，保存提示恢复正常 |

## 2. 验收

```
mvn test         PASS 46/46
e2e.sh           PASS 50/50   （真 MySQL 8.0.28，证据 target/e2e-v7.out）
Flyway           v1 → v96（本轮无新迁移）
表 / 接口         26 张业务表 / 101 个接口
原型前端          /app/ 200；31 路由读真实数据
```

界面实测（无头 Chrome + CDP，admin 登录）：

| 场景 | 结果 |
|---|---|
| 新建产品「UI-测试产品」 | 列表出现，`GET /api/products` 可查，提示「产品已保存」 |
| 新建凭证（带 PDF 附件） | `files` 非空，列表可见 |
| 新建设施 | 列表出现，层级正确 |
| 核算「重算」 | 状态 → 已计算，提示「核算完成 / 数据已刷新」 |
| 生成碳报告 | `碳排放核算报告_2026-11_v17.pdf`，指纹提示，历史列表 +1 |
| 报告历史 | 17 行，下载/删除按钮按角色渲染 |
| 企业信息 | 显示 HDJM / 华东精密制造有限公司，管理员可见「编辑」 |
| 8 个分析页 | KPI、趋势、桑基、明细均有后端数据；配额页 4 个 KPI |
| viewer | 写按钮全部移除；接口仍返回 403 |
| 设备管理页 | 写按钮隐藏（无设备 CRUD） |

测试数据已清理（产品、凭证、设施、报告各删 1 条），库内无残留。

## 3. 已知限制

| 项 | 说明 |
|---|---|
| 设备管理 CRUD | 后端只有 `GET /api/devices`，页面编辑/删除/设备导入在 `/app/` 下隐藏；要开放需先补 4 个接口 |
| 交易修改 | 后端仅支持新增与删除（原子扣减资产），前端编辑时提示「不支持修改，请删除后重新登记」 |
| 页面分页 | 列表仍为快照全量渲染 + 前端分页；数据量上万后需逐页改服务端分页 |
| 快照体积 | `/api/bootstrap` 一次返回全部台账，当前量级 ~400KB；后续可按域拆分 |
| 分析图表 | 只渲染「趋势 + 构成 + 明细」，原型里的雷达/链路图为演示形态，未接后端 |
| 定时任务 | 预警扫描、配额提醒、报告月度生成仍未接调度 |

## 4. 证据位置

- `target/surefire-reports/`（46 条断言）
- `target/e2e-v7.out`（50 项端到端）
- `target/carbonbal.jar`（38.7MB，含 `/app` 与 `/console.html`）
- 文档：`docs/architecture.md` §12、`README.md` §9–§10、需求卡 `REQ-20261125-002`

## 5. 回滚

```bash
# 前端回滚：静态资源随 jar，回退 jar 即可
git checkout -- src/main/resources/static/app   # 若在版本库中
mvn -DskipTests package
```

数据库本轮无迁移，回滚不涉及 DDL。
