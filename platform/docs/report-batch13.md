# 第十三批交付报告：租户管理与授权期（用户体系重构 P1）

> 日期：2026-11-25　范围：P1（租户管理 + 授权期 + 三级身份 + 超管代管）
> 依据：`user-system-redesign.md`（D1–D8 已定）、`user-system-api.md`、原型 `prototypes/user-system-v2.html`

## 1. 本批做了什么

| # | 能力 | 结果 |
|---|---|---|
| 1 | 租户管理分页 | `GET /platform/tenants` 返回 `{total, rows, page, size}`，支持关键词、状态、30 日内到期筛选；前端表格与分页器接服务端 |
| 2 | 授权期 | `org.license_start_at` + `org.expire_at`；开通必填起止，续期支持 3/6/12/24 个月或指定日期 |
| 3 | 到期阻断 | 登录、选租户、切租户、每次请求四道校验；到期读写都拒绝，无宽限期 |
| 4 | 到期任务 | `TenantLicenseJob` 每天 00:05 把未生效置 active、把过期置 expired，启动补跑一次，状态变化清会话缓存 |
| 5 | 三级身份 | 超级管理员（`platform_admin`）/ 租户管理员（`admin`）/ 普通用户；显示名统一，系统管理菜单仅管理员可见 |
| 6 | 超管进入租户 | `POST /auth/enter-tenant` 签发 `imp=true` 令牌，租户内角色 admin，不受租户状态与职责分离限制；顶部黄色横幅可随时退出 |
| 7 | 租户信息页 | `GET /org/license` + `/system/tenant`：授权状态、剩余天数、席位、套餐，只读 |
| 8 | 席位约束 | 新增成员校验 `seat_limit`，用尽返回 `SEAT_LIMIT`；平台调整席位不得小于当前成员数 |
| 9 | 平台 KPI | 租户总数、正常租户、30 日内到期、席位使用（只统计设了上限的租户） |
| 10 | 开通向导 | 四步：企业信息 → 管理员 → 授权与套餐 → 确认；返回一次性初始密码 |

## 2. 数据与接口

- 迁移 **V106**：加 `license_start_at`、`expired/disabled` 状态归一、`(status, expire_at)` 索引；**V107**：历史租户席位补默认 20；Flyway 到 **107（25 个迁移）**。
- 新增接口 **7 个**（155 → 162）：
  `GET /platform/tenants/{id}`、`POST /platform/tenants/{id}/renew`、`/suspend`、`/resume`、
  `GET /org/license`、`POST /auth/enter-tenant`、`POST /auth/exit-tenant`。
- 新错误码 3 个：`TENANT_PENDING`(403)、`TENANT_LICENSE_INVALID`(422)、`SEAT_LIMIT`(409)。

## 3. 关键设计

- **状态单一口径**：数据库状态可能滞后于时钟，列表与登录统一用 `effectiveStatus()`：`active` 且已过到期时间即视为 `expired`，不依赖定时任务。
- **代管不是提权**：代管令牌 `role=admin`，仅在该租户内有效；`/api/platform/**` 在代管上下文不可用，必须退出后操作平台。
- **审计留痕**：开通、修改、续期、停用、恢复、进入租户均写 `audit_log`；代管写操作按租户 `org_id` 记录。
- **席位与成员数联动**：席位只能上调到不低于现有成员数，避免“改小后无法新增也删不掉”的死锁。

## 4. 验收

| 项 | 结果 |
|---|---|
| 单元测试 | **65/65**（新增 `TenantLicenseTest` 4 条） |
| 端到端 | **100/102**（2 项短信日配额，环境性） |
| 静态门禁 | 11/11（37 个菜单入口） |
| UI 门禁 | 5/5（36 路由、0 控制台错误） |
| 数据一致性 | 29/29 |
| 文档一致性 | 21 份活文档 0 处过期口径 |
| 性能预算 | 14/14 |

新增用例覆盖：未生效拒绝登录、停用后读请求同样 423、到期后读写 403、续期后恢复、超管可进入已到期租户并可退出。

## 5. 边界与后续

- 角色表 `role/role_page` 与页面级权限属于 **P2**；本期角色仍是 `user_tenant.role`，`user_type` 由 `role=admin` 推导。
- 租户管理员「系统管理」目前只有账号管理与租户信息；角色管理、审计日志在 P2/P3 补齐。
- 演示租户「华东精密制造有限公司」授权期显示“长期”（`expire_at` 为空），如需演示到期效果可在平台端改到期日或直接停用。
