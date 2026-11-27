# 权限模型（三级身份 + 页面级角色）

> 更新：2026-11-26（用户体系重构 P2）。取代 v1 的“7 角色 × 22 能力”矩阵。
> 一句话：**层级决定身份，角色决定能进哪些页面，动作不再按角色分级。**

## 1. 三级身份

| 层级 | 标识 | 数据范围 | 能做什么 |
|---|---|---|---|
| 超级管理员 | `app_user.platform_admin=1`（orgId=0） | 跨租户 | 租户开通/续期/停用/套餐席位；可进入任意租户并以租户管理员身份操作；不受租户状态、职责分离限制 |
| 租户管理员 | `user_tenant.user_type=tenant_admin` | 本企业 | 全部业务页面 + 系统管理（账号、角色、租户信息、审计）；可锁定/解锁核算 |
| 普通用户 | `user_tenant.user_type=user` + `role_id` | 本企业 | 只能进入角色勾选的页面；页面内可新增/编辑/流转，删除仍限租户管理员 |

约束：
- 普通用户必须绑定且只绑定一个角色；租户管理员不绑定角色（`role_id` 为空）。
- 租户管理员不因“角色”受限，`pages` 视为 `*`。
- 超级管理员账号不能是任何租户的成员（D3）。
- 平台账号**本身不持有任何业务页面**（令牌 `pages` 为空），登录后只看到「平台管理」；需要业务数据时通过「进入租户」获取该租户的 `*` 页面集合。

### 1.1 成员身份（用户类型）

| 字段 | 取值 | 作用 | 生效位置 |
|---|---|---|---|
| 用户类型 `user_type` | 普通用户 `user` / 租户管理员 `tenant_admin` | 决定组织身份：租户管理员不绑角色、页面视为 `*`、可进系统管理；普通用户必须绑一个角色 | 登录签发令牌、页面/接口鉴权、系统管理菜单 |

说明：
- 租户管理员仍受企业授权期约束，不是超级管理员；系统保护最后一名管理员（不能停用/移出/降级）。
- 成员类型（内部/外部）、数据范围（ORG/SELF）、成员级授权到期已在 V109 移除：
  所有成员按内部员工处理，数据按企业范围可见，成员有效期只受企业授权期约束。
- 因此 `/api/external/**`、外部门户与 `external_grant` 一并下线；历史外部账号迁移时统一停用。

## 2. 页面权限

- 页面目录：34 个业务页面、13 个分组，编码见 `identity/PageCatalog`，与 `assets/menu.js` 的 `page` 一一对应。
- 角色 = 名称 + 说明 + 一组页面编码；**不含动作位、不含数据范围、不分内置/自定义**。
- 默认角色【运营】：开通租户时自动创建，默认拥有全部页面，不可删除；新增页面时启动自动补齐。
- 每租户 ≤30 个角色；名称租户内唯一；至少保留一个角色。
- 页面集合写入 JWT 的 `pages` 声明；角色变更会让该角色下成员的令牌失效（需重新登录）。

生效位置：

| 位置 | 行为 |
|---|---|
| 菜单 | 只渲染 `pages` 内的菜单；分组下无可用项则整组隐藏 |
| 路由 | 直接输入未授权 hash 显示“没有该功能的访问权限”（系统管理/平台管理按菜单标记拦截） |
| 接口 | `JwtAuthFilter` 按 URI → `page_code` 校验，未授权 403 `PAGE_FORBIDDEN` |
| 快照 | `/api/bootstrap` 按页面裁剪：无权页面的数据键不下发 |

### 2.1 统一入口的页面校验

有些接口不按 URI 区分模块，必须在接口内部按参数校验，否则会绕过页面权限：

| 接口 | 校验规则 |
|---|---|
| `GET /api/export?resource=` | `resource` → 页面（products→product.list、evidences→evidence.list、readings→device.data…共 13 项） |
| `GET /api/import/template?resource=`、`POST /api/import?resource=` | 同一份 resource → 页面映射（5 项） |
| `GET/POST /api/files`、`GET /api/files/{id}/download` | 需 `evidence.list` 或 `third.report`（附件只服务于这两个页面） |
| `GET /api/emission-factors` | 归入 `accounting.list`（因子是核算输入） |
| `GET /api/jobs`、`POST /api/jobs/{name}/run` | 仅租户管理员（运维动作） |

未通过时统一返回 403 `PAGE_FORBIDDEN`，与 URI 前缀校验同一口径。

## 3. 动作权限

- 本期**不做**角色级动作权限：能进入页面即可新增、编辑、导入、计算、提交、复核、批准、导出。
- 唯一保留的护栏：**删除与成员移出仅租户管理员**（防止误删，后端 `@PreAuthorize("hasRole('ADMIN')")`）。
- 系统管理（账号、角色、租户信息、审计）不进入页面目录，普通用户永远拿不到。
- 审计只读：`GET /api/audit-logs`、`/facets` 需 `hasRole('ADMIN')`；平台审计 `GET /api/platform/audit` 需 `PLATFORM_ADMIN`。平台操作写入 `audit_log.org_id = 0`，与租户审计互相隔离。

## 4. 职责分离（按人校验，不是角色配置）

1. 同一核算期间，编制人 ≠ 复核人；
2. 同一期间，复核人 ≠ 批准人；
3. 违反返回 409 `SOD_VIOLATION`；
4. 租户管理员同样受这三条约束；
5. **超级管理员不受约束**，可一人完成编制、复核、批准。

## 5. 租户状态对权限的影响

| 租户状态 | 登录 | 已登录请求 | 超级管理员 |
|---|---|---|---|
| `pending` 未生效 | 拒绝 `TENANT_PENDING` | 403 | 可进入 |
| `active` 正常 | 允许 | 放行 | 可进入 |
| `suspended` 停用 | 拒绝 `TENANT_SUSPENDED` | 423 | 可进入 |
| `expired` 到期 | 拒绝 `TENANT_EXPIRED` | 403（读写都拒绝） | 可进入 |

## 6. 实现位置

| 层 | 文件 |
|---|---|
| 页面目录 | `backend/src/main/java/com/carbonbal/identity/PageCatalog.java` |
| 角色服务 | `identity/RoleService.java`、`RoleController.java`（`/api/roles`） |
| 身份与页面写入令牌 | `auth/AuthController`、`auth/JwtService`、`auth/AuthStateService` |
| 请求校验 | `auth/JwtAuthFilter`、`auth/PageAccess` |
| 快照裁剪 | `bootstrap/BootstrapService#filterByPages` |
| 审计 | `audit/AuditService`（`page/facets`、`PLATFORM_ORG_ID`）、`audit/AuditController`、`platform/PlatformService#audit` |
| 前端 | `assets/menu.js`（page 编码）、`index.html`（菜单过滤）、`framework.js`（路由守卫）、`pages-system.js`（账号/角色/审计页）、`pages-platform.js`（概览/租户/平台审计） |
