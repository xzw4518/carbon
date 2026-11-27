# 用户体系 v2 · 接口清单与验收点（简化版）

> 配套：产品方案 `user-system-redesign.md`，页面原型 `自研原型/prototypes/user-system-v2.html`。
> 角色模型已简化：**角色 = 可进入的功能页面集合**。本期不做动作级权限、不做数据范围、不区分内置/自定义。
> 约定沿用现有：成功 `{status:0, body}`，失败 `{status:"CODE", body:{message, detail}}`。

## 1. 数据模型

| 表 | 字段 | 说明 |
|---|---|---|
| `org` | `license_start_at` | 授权开始日；新增 |
| `org` | `license_expire_at` | 复用现有 `expire_at`，语义改为“授权到期” |
| `org` | `status` | `pending / active / suspended / expired` |
| `role` | `id, org_id, code, name, description, version, created_by, updated_at` | 租户内角色；只描述“能进哪些页面” |
| `role_page` | `role_id, page_code` | 一行一个页面；唯一键 `(role_id, page_code)` |
| `user_tenant` | `user_type` | `TENANT_ADMIN / NORMAL`；租户管理员不再是角色 |
| `user_tenant` | `role_id` | 普通用户必填；租户管理员为空 |
| `user_tenant` | `status` | `active / disabled / removed` |

约束：

- 每租户角色数 ≤ 30。
- 普通用户只能绑定一个角色；租户管理员不绑定角色。
- 至少保留一个角色；删除最后一个角色返回 409 `ROLE_LAST`。
- 角色删除前必须无用户，否则 409 `ROLE_IN_USE`。
- 新租户开通时自动创建默认角色【运营】，默认拥有全部业务页面（不含系统管理）。

## 2. 页面权限目录

`GET /api/permissions/pages` 返回分组页面树。角色勾选的就是这些 `page_code`：

| 分组 | 页面（page_code） |
|---|---|
| 首页 | `home` |
| 企业管理 | `enterprise.info`、`group.overview` |
| 预算管理 | `quota.list` |
| 产值管理 | `product.list`、`production.value`、`total.value` |
| 碳排放核算 | `accounting.list` |
| 碳足迹管理 | `footprint.list`、`footprint.analysis`、`supplier.list`、`supplier.analysis` |
| 碳资产管理 | `asset.list`、`ccer.list`、`trade.list` |
| 碳核查支撑 | `evidence.list`、`third.report` |
| 报告查询 | `report.carbon`、`report.energy`、`report.footprint` |
| 能碳分析 | `analysis.energy`、`analysis.strategy`、`analysis.benchmark`、`analysis.carbon`、`analysis.quota`、`analysis.flow` |
| 设备设施 | `facility.list`、`device.list`、`device.data` |
| 预警管理 | `alert.event`、`alert.setting`、`alert.record` |
| 节能管理 | `retrofit.list`、`energy.mgmt` |

系统管理（`system.users`、`system.roles`、`system.tenant`、`system.audit`）**不在页面目录中**，只对租户管理员与超级管理员开放。

默认【运营】页面集合 = 上表全部业务页面（34 个，不含系统管理）。

### 2.1 页面与接口前缀（实现映射）

| page_code | 接口前缀 |
|---|---|
| `home` | `/api/overview`、`/api/bootstrap` |
| `enterprise.info` | `/api/org` |
| `group.overview` | `/api/group/**` |
| `quota.list` | `/api/quota-years` |
| `product.list` | `/api/products` |
| `production.value` | `/api/production-values` |
| `total.value` | `/api/total-values` |
| `accounting.list` | `/api/accounting-periods/**` |
| `footprint.list` | `/api/footprints` |
| `footprint.analysis` | `/api/analysis/footprint/**` |
| `supplier.list` | `/api/suppliers` |
| `supplier.analysis` | `/api/analysis/supplier/**` |
| `asset.list` / `ccer.list` | `/api/carbon-assets`（`type` 区分） |
| `trade.list` | `/api/carbon-trades` |
| `evidence.list` | `/api/evidences`、`/api/files/**` |
| `third.report` | `/api/third-party-reports` |
| `report.carbon` / `report.energy` / `report.footprint` | `/api/reports`（`type` 区分） |
| `analysis.*` | `/api/analysis/**`（按子路径映射） |
| `facility.list` | `/api/facilities` |
| `device.list` | `/api/devices` |
| `device.data` | `/api/device-readings/**` |
| `alert.event` / `alert.setting` / `alert.record` | `/api/alert-events` / `/api/alert-rules` / `/api/alert-records` |
| `retrofit.list` / `energy.mgmt` | `/api/retrofits` / `/api/energy-mgmt` |

> 一个接口被多个页面共用时，取最宽页面；跨页面的聚合接口（如 `/api/bootstrap`）必须按用户 `pages` 过滤返回内容。

## 3. 认证与上下文

### 3.1 POST /api/auth/login

```json
// req
{ "phone": "13800000001", "password": "******" }
// 或 { "phone": "13800000001", "code": "123456" }
```

```json
// 200
{ "status": 0, "body": {
  "token": "…",
  "user": { "id": 1, "phone": "138****0001", "displayName": "陈立", "level": "TENANT_ADMIN" },
  "tenants": [{ "orgId": 1, "name": "华东精密", "roleName": "租户管理员", "expireAt": "2027-03-31" }],
  "needTenantSelect": false,
  "mustChangePassword": false
}}
```

失败分支：

| 场景 | HTTP | code |
|---|---|---|
| 手机号/密码错 | 401 | `AUTH_BAD_CREDENTIAL` |
| 账号停用 | 423 | `ACCOUNT_DISABLED` |
| 租户未生效 | 423 | `TENANT_PENDING` |
| 租户停用 | 423 | `TENANT_SUSPENDED` |
| 租户到期 | 423 | `TENANT_EXPIRED`（detail 带到期日） |
| 无任何可用租户 | 403 | `TENANT_NOT_FOUND` |

### 3.2 GET /api/auth/me

```json
{ "status": 0, "body": {
  "user": { "id": 1, "displayName": "陈立" },
  "level": "TENANT_ADMIN",
  "org": { "id": 1, "name": "华东精密", "licenseExpireAt": "2027-03-31", "daysLeft": 126 },
  "role": { "id": 5, "name": "运营" },
  "pages": ["home", "enterprise.info", "quota.list", "product.list", "…"],
  "platformContext": false
}}
```

- 普通用户返回角色与页面编码数组；租户管理员返回全部业务页面 + `system.*`；超级管理员返回 `["*"]`。
- 前端菜单、路由、按钮只依赖 `pages`，不再判断角色字符串。

### 3.3 POST /api/auth/enter-tenant（超级管理员）

```json
{ "orgId": 3 }
```

- 返回新 token（Cookie 同步），`platformContext=true`，`org` 为目标租户，`pages=["*"]`；
- 不受租户状态/授权期限制；
- 写平台审计 `platform_enter`。

### 3.4 POST /api/auth/exit-tenant

恢复平台上下文；写审计 `platform_exit`。

### 3.5 POST /api/auth/switch-tenant

切换本账号所属租户；目标租户必须 `active` 且在授权期内，否则 423。

## 4. 平台管理（仅超级管理员）

### 4.1 GET /api/platform/overview

```json
{ "tenants": { "total": 12, "active": 9, "expiring30": 2, "expired": 1, "suspended": 0 },
  "seats": { "used": 106, "limit": 145 },
  "recentExpiring": [{ "orgId": 3, "name": "北方热电", "expireAt": "2026-12-10", "daysLeft": 15 }] }
```

### 4.2 GET /api/platform/tenants

参数：`page,size,keyword,status,expiringDays,sort`。
返回：`orgId, name, code, status, licenseStartAt, licenseExpireAt, daysLeft, seatUsed, seatLimit, adminName, adminPhone`。

### 4.3 POST /api/platform/tenants

```json
{
  "name": "华东精密制造有限公司",
  "code": "HDJM",
  "industry": "制造业",
  "scale": "中型",
  "adminPhone": "13800000001",
  "adminName": "陈立",
  "licenseStartAt": "2026-11-25",
  "licenseExpireAt": "2027-11-24",
  "plan": "standard",
  "seatLimit": 30
}
```

```json
// 200
{ "status": 0, "body": {
  "orgId": 13, "tenantCode": "HDJM", "tempPassword": "一次性初始密码",
  "defaultRole": { "id": 31, "name": "运营", "pages": 34 }
}}
```

创建事务：org + 默认角色【运营】+ 租户管理员账号/成员 + 默认设施 + 平台审计。

### 4.4 PATCH /api/platform/tenants/{orgId}

可改：`name, plan, seatLimit, licenseStartAt, licenseExpireAt, remark`。
- 缩短到期日至过去 → 立即置 `expired` 并踢会话；
- 续期 → 状态回 `active`，写审计 `renew`；
- `code` 不可改。

### 4.5 POST /api/platform/tenants/{orgId}/suspend | resume

- 停用：`suspended`，该租户所有账号 `token_version+1`；必填 `reason`；
- 恢复：`active`（若已过期需先续期）；
- 均写平台审计。

## 5. 用户管理（租户管理员 / 超级管理员）

### 5.1 GET /api/users

参数：`page,size,keyword,userType,roleId,status`。
返回：`membershipId, userId, name, phone(脱敏), userType, roleId, roleName, status, lastLoginAt`。

### 5.2 POST /api/users

```json
{ "phone": "13800000002", "displayName": "张敏", "userType": "NORMAL", "roleId": 31 }
```

- `userType=TENANT_ADMIN` 时 `roleId` 必须为空；
- 手机号已存在 → 复用账号，仅新增成员关系；
- 席位已满 → 409 `SEAT_LIMIT`；
- 未指定 `roleId` 时默认绑定【运营】；
- 返回 `membershipId, tempPassword`。

### 5.3 PATCH /api/users/{membershipId}

可改：`displayName, userType, roleId`。
- 普通用户 → 租户管理员：清空 `roleId`；
- 租户管理员 → 普通用户：必须指定 `roleId`；若是最后一名管理员 → 409 `MEMBER_LAST_ADMIN`；
- 不能修改自己为普通用户（409 `SELF_OPERATION`）。

### 5.4 其它动作

| 接口 | 说明 | 关键错误 |
|---|---|---|
| `POST /api/users/{id}/reset-password` | 返回一次性临时密码 | — |
| `POST /api/users/{id}/unlock` | 失败次数清零、解除锁定 | — |
| `POST /api/users/{id}/disable` | 停用成员，`token_version+1` | 不能停用自己 `SELF_OPERATION` |
| `POST /api/users/{id}/enable` | 启用成员；检查席位 | `SEAT_LIMIT` |
| `POST /api/users/{id}/handover` | 把创建人/负责人转给他人 | 目标必须是本租户启用用户 |
| `DELETE /api/users/{id}` | 移出成员；账号保留 | 最后管理员 `MEMBER_LAST_ADMIN` |
| `GET /api/users/{id}/login-logs?limit=50` | 该成员登录记录 | — |
| `GET /api/users/export` / `POST /api/users/import` | CSV 导入导出 | `VALIDATION` |

## 6. 角色管理（租户管理员 / 超级管理员）

### 6.1 GET /api/roles

返回：`id, name, description, userCount, pageCount, pages, updatedAt`。

### 6.2 POST /api/roles

```json
{
  "name": "财务查看",
  "description": "看产值与报告",
  "pages": ["home", "product.list", "production.value", "report.carbon", "report.energy"]
}
```

校验顺序：

1. 名称租户内唯一（409 `ROLE_NAME_DUPLICATE`）；
2. 角色数 < 30（409 `ROLE_LIMIT`）；
3. `pages` 必须都在目录内（400 `VALIDATION`）；
4. 至少勾选 1 个页面（400 `VALIDATION`）。

### 6.3 PATCH /api/roles/{id}

- 可改名称、说明、页面集合；
- 变更后 `version+1`，清空该角色用户的页面缓存（≤30 秒生效）；
- 审计记录 before/after 页面集合。

### 6.4 DELETE /api/roles/{id}

- 有用户 → 409 `ROLE_IN_USE`（detail 返回人数）；
- 最后一个角色 → 409 `ROLE_LAST`；
- 删除后写审计。

### 6.5 GET /api/roles/{id}/users

返回该角色下用户（`membershipId, name, phone, status`），供转移。

### 6.6 GET /api/permissions/pages

返回分组页面树：

```json
[{ "group": "产值管理", "pages": [
  { "code": "product.list", "name": "产品列表" },
  { "code": "production.value", "name": "产品产值" },
  { "code": "total.value", "name": "生产总值" }
]}]
```

## 7. 审计

### GET /api/audit-logs

参数：`page,size,actor,module,action,entity,entityId,from,to`。
返回：`id, actor, actorLevel, module, action, entity, entityId, before, after, orgId, at`。

- 租户管理员只看本租户；超级管理员可看全部；
- 平台动作（开通/续期/停用/进入租户）标记 `actorLevel=SUPER_ADMIN`。

## 8. 错误码新增

| code | HTTP | 场景 |
|---|---|---|
| `TENANT_PENDING` | 423 | 租户未到授权开始日 |
| `TENANT_EXPIRED` | 423 | 租户授权到期（复用现有码） |
| `ROLE_LIMIT` | 409 | 角色数超 30 |
| `ROLE_IN_USE` | 409 | 角色仍有用户 |
| `ROLE_LAST` | 409 | 删除最后一个角色 |
| `ROLE_NAME_DUPLICATE` | 409 | 角色名重复 |
| `SEAT_LIMIT` | 409 | 席位已满 |

> 职责分离（同一期间编制≠复核≠批准）仍是**业务规则**，不是角色权限；违反时返回现有 `SOD_VIOLATION`。

## 9. 服务端校验顺序

1. 会话有效（401）；
2. 租户上下文与状态（423；超级管理员跳过）；
3. 用户层级（是否租户管理员/超级管理员，决定能否进系统管理）；
4. **页面权限**：请求所属 `page_code` 是否在用户 `pages` 中（403；管理员/超管跳过）；
5. 对象归属 `org_id`（403/404）；
6. 业务规则（400/409）；
7. 写审计。

> 每个业务接口必须声明所属 `page_code`（注解或路径映射）；`/api/bootstrap` 只返回用户有权限页面的数据，避免“菜单藏了但接口能拿到”。

## 10. 验收点

1. 超级管理员登录 → 平台概览；进入任意租户 → 全页面可见、可操作。
2. 租户到期：该租户账号登录 423 `TENANT_EXPIRED`；超级管理员仍可进入。
3. 续期后同一账号立即恢复登录，数据与角色不变。
4. 新租户开通后自动存在【运营】角色，默认 34 个业务页面。
5. 租户管理员新增普通用户不选角色 → 默认【运营】。
6. 把【运营】的“报告查询”取消后，该角色用户菜单里没有报告，直接访问报告接口 403。
7. 新建角色只勾 3 个页面 → 该角色用户只看到 3 个菜单。
8. 第 31 个角色 → 409 `ROLE_LIMIT`。
9. 删除有用户的角色 → 409 `ROLE_IN_USE`。
10. 删除唯一角色 → 409 `ROLE_LAST`。
11. 普通用户直接调未授权页面接口 → 403，且不返回数据。
12. 最后一名租户管理员被降级 → 409 `MEMBER_LAST_ADMIN`。
13. 席位满后新增用户 → 409 `SEAT_LIMIT`。
14. 同一手机号加入第二租户 → 复用账号、新增成员关系。
15. 租户 A 到期，同一手机号在租户 B 仍可登录。
16. 超级管理员同一期间完成编制、复核、批准 → 全部成功。
17. 普通用户同一期间不能既编制又复核 → 409 `SOD_VIOLATION`（业务规则仍在）。

## 9. 实施状态（P1，2026-11-25）

P1「租户管理与授权期 + 三级身份」已落地，以下按实际实现记录差异，后续阶段以此为准：

| 项 | 实现 |
|---|---|
| 租户状态 | `pending / active / suspended / expired`；历史 `disabled` 归入 `suspended` |
| 授权期字段 | `org.license_start_at`（日期）+ `org.expire_at`（到期日 23:59:59），迁移 V106 |
| 平台接口 | `GET /platform/tenants`（分页）、`GET /platform/tenants/{id}`、`POST /platform/tenants`、`PATCH /platform/tenants/{id}`、`POST /{id}/renew`、`POST /{id}/suspend`、`POST /{id}/resume`、`GET /platform/stats` |
| 超级管理员进入租户 | `POST /auth/enter-tenant`、`POST /auth/exit-tenant`；令牌带 `imp=true`，租户内角色为 `admin`，不受租户状态与职责分离限制 |
| 登录拦截 | 登录、选租户、切换租户三处均校验授权期；到期/停用返回 `TENANT_EXPIRED` / `TENANT_SUSPENDED`，未生效返回 `TENANT_PENDING` |
| 请求拦截 | 每次请求按租户状态校验，到期读写都拒绝（无宽限期）；租户状态变化清成员会话缓存 |
| 到期任务 | `TenantLicenseJob` 每天 00:05 生效/置到期，启动时补跑一次 |
| 租户信息页 | `GET /org/license` + `/system/tenant`（只读） |
| 席位 | 新增成员校验 `org.seat_limit`，用尽返回 `SEAT_LIMIT` |
| 角色 | 本期角色仍是 `user_tenant.role`（admin/filler/reviewer/approver/viewer）；`role/role_page` 表与页面级权限在 P2 落地 |
| 三级身份显示 | 管理员显示为「租户管理员」，平台账号显示为「超级管理员」；`user_type` 在 P2 随角色表引入 |

## 10. 实施状态（P2，2026-11-26）

角色与页面权限已落地，与设计稿的差异如下：

| 项 | 实现 |
|---|---|
| 表 | `tenant_role` / `tenant_role_page`（避开 H2 保留字 `role`）；`user_tenant.user_type`、`role_id`（V108） |
| 角色接口 | `GET /api/roles`、`GET /api/roles/pages`、`GET /api/roles/{id}`、`POST`、`PATCH`、`DELETE`、`GET /{id}/users` |
| 页面目录 | `identity/PageCatalog`：13 组 34 页；`home` 强制包含；页面编码与 `menu.js` 一致 |
| 角色约束 | ≤30 个；名称唯一；默认【运营】不可删；有用户不可删；至少保留一个 |
| 三级身份 | `tenant_admin` / `user`；`user_tenant.role` 归一为 `admin` / `user`，旧的 filler/reviewer 等由状态层自动映射 |
| 页面下发 | JWT `pages` 声明；租户管理员为 `*`，普通用户为该角色页面集合 |
| 接口校验 | `JwtAuthFilter` 按 URI → `page_code`，未授权 403 `PAGE_FORBIDDEN` |
| 快照裁剪 | `/api/bootstrap` 移除无权页面对应的数据键（products / suppliers / reports / accounting / quota 等 13 类） |
| 动作权限 | 不按角色分级；保留“删除/移出仅租户管理员”的护栏 |
| 会话 | 角色改动会让该角色下成员 `token_version+1`，旧令牌 401，需重新登录 |
| 前端 | `/system/roles` 角色管理（页面树）、`/system/users` 用户类型 + 角色、菜单过滤、路由守卫 |

## 11. 实施状态（P3，2026-11-26）

平台概览、审计与到期提醒已落地：

| 项 | 实现 |
|---|---|
| 平台概览 | `GET /api/platform/overview` → `{stats, expiring, recent}`；`expiring` 为 30 日内到期（含已到期）按剩余天数升序，最多 8 条；`recent` 为最近 8 条平台操作 |
| 超管落地页 | `#/platform/overview`；平台管理菜单＝概览 / 租户管理 / 平台审计 |
| 平台审计 | `GET /api/platform/audit`（分页）：`page/size/action/keyword/from/to`；`actionLabel` 按平台口径（create→开通、update→设置）；`targetName` 解析租户名，已删除租户返回 null |
| 平台审计归属 | `audit_log.org_id = 0`（`AuditService.PLATFORM_ORG_ID`）；开通/设置/续期/停用/恢复/进入/退出全部显式写入 |
| 租户审计 | `GET /api/audit-logs`（分页）：`page/size/entity/action/actor/keyword/from/to`；`GET /api/audit-logs/facets` 返回本租户出现过的模块与动作 |
| 审计权限 | `@PreAuthorize("hasRole('ADMIN')")`：仅租户管理员（含代管超管）；普通用户 403 |
| 中文口径 | `AuditService.entityLabel / actionLabel` 为唯一映射；历史空标签或与动作同名的标签在读时补齐 |
| 到期提醒 | 登录 / `GET /api/auth/me` / 进入租户三处返回 `licenseEndAt`、`licenseRemainDays`、`licenseStatus`、`plan`、`seatLimit` |
| 提醒阈值 | 30 天内首屏横幅（`#license-banner`），7 天内转红；平台概览与租户列表按 30/7 天标色 |
| 前端 | `pages-platform.js`（`/platform/overview`、`/platform/audit`）、`pages-system.js`（`/system/audit`）、`api-client.js`（落地页 + 横幅） |
| 回归修复 | `tenant_role` / `tenant_role_page` 加入行级隔离豁免表（签发令牌与启动补齐时无租户上下文）；`pages` 为空不再回落为 `*`，改为 `-`（无页面） |
| 改密提示 | 移除“每次新会话无条件弹改密窗”，只保留首次登录强制改密与 ≤15 天到期 toast |

