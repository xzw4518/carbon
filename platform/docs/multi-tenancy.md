# 多租户方案（决策稿）

> 日期：2026-11-25　状态：待决策　影响面：数据库、认证、全部查询、文件存储、运维
> 结论先行：**默认走「共享库共享表 + 强制行级隔离」，同时预留「大客户独立库」逃生舱**；不采用每租户一套 schema。

---

## 1. 现状审计（先厘清"已经有什么"）

### 1.1 已经具备

| 能力 | 现状 |
|---|---|
| 租户标识 | `org` 表即租户；JWT 里带 `org` 声明，`CurrentUser.orgId()` 取出 |
| 数据隔离 | 27 张业务表里 24 张有 `org_id`，Service 普遍按 `CurrentUser.orgId()` 过滤 |
| 越权拦截 | 跨企业访问主数据/业务记录返回 403（`require()` 里统一判断） |
| 唯一键 | 大多数已按租户：`(org_id, code)`、`(org_id, period)`、`(org_id, name, asset_year)` |
| 文件元数据 | `file_object` 带 `org_id`，下载前校验 |

### 1.2 审计出的缺口（这是本轮要补的重点）

| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| 1 | **审计日志跨租户可见** | `audit_log` 无 `org_id`；`AuditService.list()` 只按 entity 过滤 | A 企业能查到 B 企业的操作记录 |
| 2 | 明细表靠父表间接隔离 | `accounting_line`、`period_unlock` 无 `org_id`，只按 `period_id` 查 | 一旦有人直查明细表就漏；子查询/JOIN 场景容易出错 |
| 3 | 隔离靠手写 | 每个 Service 自己写 `.eq(orgId)`；已发生过 `DeviceController` 漏过滤 | 新增页面/接口时容易再漏，且回归测试覆盖不到 |
| 4 | 用户名全局唯一 | `uk_user_username UNIQUE (username)` | 两家客户都有 `admin` 时无法共存 |
| 5 | 无平台超管 | `app_user.role` 只有 admin/filler/viewer，且都挂在某个 org | 无法做租户开通、停用、用量查看 |
| 6 | 文件未按租户分目录 | 存储键是 `yyyy-MM-dd/uuid.ext` | 运维层面无法按租户导出/清理/限额 |

换句话说：**"数据能隔离" 已经有 80%，"租户能被运营" 是 0%，"隔离不被写漏" 靠自觉。**

---

## 2. 三种隔离方案

| 维度 | A. 共享库共享表（Pooled） | B. 共享库独立 schema（Bridge） | C. 独立库（Silo） |
|---|---|---|---|
| 隔离强度 | 中（靠 `org_id` + 代码强制） | 较高（schema 级） | 最高（物理隔离） |
| 单租户故障影响 | 可能波及 | 较小 | 不波及 |
| 迁移成本（当前 28 表） | 低 | 中（每租户一份 DDL + Flyway 多点） | 高（连接路由 + 迁移编排） |
| 运维成本 | 低（一套备份/监控） | 中 | 高（N 套实例/备份） |
| 跨租户统计 | 一条 SQL | 需聚合多 schema | 需数仓/抽取 |
| 大客户合规（数据不出境/独立备份） | 弱 | 中 | 强 |
| 适合阶段 | 早期 SaaS、租户 < 500 | 中型、强合规 | 大客户、私有化 |

**推荐：A 作为默认，C 作为逃生舱。**
理由：当前是单客户试点 + 26 张表 + 小团队，B 的 schema 隔离带不来实质收益却要维护 N 份迁移；真正有大客户提"独立部署"时，用 C 的私有化交付（同一份 jar + 独立库）即可，不需要改业务代码。

---

## 3. A 方案设计

### 3.1 租户模型

`org` 升级为租户主表，新增：

| 字段 | 说明 |
|---|---|
| `status` | active / suspended / closed，停用即全租户拒绝登录 |
| `plan` | 套餐标识（free / standard / enterprise），驱动配额 |
| `seat_limit` | 用户数上限 |
| `storage_limit_mb` | 文件空间上限 |
| `expire_at` | 到期日（到期转只读） |

不改表名，避免全量改名；对外统一叫「租户 = org」。

### 3.2 用户与权限（三级）

| 角色 | 归属 | 能做什么 |
|---|---|---|
| `platform_admin` | 不属任何租户 | 开通/停用租户、看用量、看审计**统计**（默认不看业务明细） |
| `admin` | 某租户 | 租户内全部业务 + 用户管理 |
| `filler` | 某租户 | 录入与提交 |
| `viewer` | 某租户 | 只读 |

JWT 增加 `scope`：`platform` / `tenant`；平台 token 不带 `org`，访问 `/api/**` 业务接口直接 403。

登录：`(org_code, username, password)`；`app_user` 唯一键改为 `(org_id, username)`，`org_id` 允许 NULL（仅平台账号）。

### 3.3 隔离的"强制"机制（核心）

四层，缺一不可：

1. **自动加条件**：MyBatis-Plus `TenantLineInnerInterceptor`，对白名单外的表自动追加 `org_id = ?`；
   白名单：`org`、`app_user`、`flyway_schema_history`、`emission_factor`（国家级因子，全租户共享）。
2. **自动填充**：`MetaObjectHandler` 在 insert 时写 `org_id`，业务代码不再手工 set。
3. **越权矩阵测试**：每个域至少 2 条断言——用 B 租户 token 读/改 A 租户的记录，必须 403 或空集；
   新增域不补测试则 CI 失败（测试类命名约定 `*TenantTest`）。
4. **静态门禁**：脚本扫描 `src/main/java`，出现不带任何 org 条件的 `selectList/selectOne/update/delete`
   且表不在白名单 → 报警（先告警，稳定后转阻断）。

### 3.4 数据补齐（一次性迁移 V99）

```sql
ALTER TABLE audit_log       ADD COLUMN org_id BIGINT NULL;
ALTER TABLE accounting_line ADD COLUMN org_id BIGINT NULL;
ALTER TABLE period_unlock   ADD COLUMN org_id BIGINT NULL;

UPDATE accounting_line l JOIN accounting_period p ON p.id = l.period_id SET l.org_id = p.org_id;
UPDATE period_unlock u JOIN accounting_period p ON p.id = u.period_id SET u.org_id = p.org_id;
-- audit_log 存量无法可靠归属：按 actor 反查 app_user，查不到则置 NULL（仅平台可见）
UPDATE audit_log a JOIN app_user u ON u.username = a.actor SET a.org_id = u.org_id;
```

用户名唯一键：

```sql
ALTER TABLE app_user DROP INDEX uk_user_username;
CREATE UNIQUE INDEX uk_user_org_name ON app_user (org_id, username);
```

### 3.5 文件隔离

存储键从 `yyyy-MM-dd/uuid.ext` 改为 `{orgId}/yyyy-MM/uuid.ext`；
下载、删除、导出都校验 `file_object.org_id`；租户注销时按目录整体清理。

### 3.6 租户生命周期 API（平台端）

| 接口 | 说明 |
|---|---|
| `GET /api/platform/tenants` | 租户列表（含用户数、文件占用、最近活跃） |
| `POST /api/platform/tenants` | 开通：建 org + 租户管理员 + 默认主数据 |
| `PUT /api/platform/tenants/{id}` | 改名/套餐/配额/状态 |
| `POST /api/platform/tenants/{id}/suspend` | 停用（立即踢下线：JWT 校验 org.status） |
| `POST /api/platform/tenants/{id}/export` | 全量导出（合规） |
| `GET /api/platform/usage` | 用量汇总（存储/用户/接口调用） |

平台端前端独立入口 `/platform`，与租户端 `/app` 分开，不共用菜单。

---

## 4. 分期与验收

| 期 | 范围 | 验收 |
|---|---|---|
| **P0 隔离加固**（1 轮） | 补 3 张表 org_id + 回填；租户插件 + 自动填充；审计隔离；文件分目录；越权矩阵测试 | 用 B 租户 token 读 A 租户审计/明细/文件 → 403 或空；`mvn test` 新增 ≥10 条越权断言 |
| **P1 租户运营**（1 轮） | 租户状态/套餐/配额；平台超管与 `/platform`；用户名改 `(org, username)`；登录带租户码 | 开通→登录→停用→无法登录，全链路 e2e |
| **P2 逃生舱**（按需） | 按 org 路由独立数据源；私有化交付清单 | 单租户独立库启动并通过同一套 e2e |

P0 是"现在就必须做"的，因为它修的是**真实存在的跨租户泄漏**（审计日志）；P1 决定能不能对外卖；P2 只在大客户要求时做。

---

## 5. 风险

| 风险 | 应对 |
|---|---|
| 租户插件改 SQL，可能与 `.last("LIMIT")`、原生 SQL、JOIN 冲突 | P0 先在测试环境全量跑 e2e（64 项）+ 51 条单测；冲突表加白名单并写明原因 |
| 存量 `audit_log` 无法回填 | 查不到归属的置 NULL，仅平台可见；文档标注"v99 之前的日志不区分租户" |
| 用户名改唯一键影响现有账号 | 迁移时校验无重名（当前 3 个账号），失败即中止并输出冲突清单 |
| 平台超管越权查看业务数据 | 默认只读统计；需要"代维"必须显式开启并记审计 |
| 停用租户后 token 仍有效 | JWT 校验时查 `org.status`（加缓存，30 秒过期） |

---

## 6. 决策记录（2026-11-25，H1 确认）

| # | 决策项 | 选择 |
|---|---|---|
| 1 | 隔离级别 | **A 共享库共享表 + 预留独立库（C）逃生舱**；不做 schema 隔离 |
| 2 | 登录方式 | **全局唯一用户名**（保持现状，不加租户码） |
| 3 | 平台超管 | **暂不做运营端**；租户开通仍由 DBA 执行 SQL，后续按需再上 |
| 4 | 本期范围 | **P0 隔离加固**（P1/P2 暂不做） |

## 7. 实施状态（2026-11-25）

**P0 已按 H1 要求回退。** 原因：用户体系要重新设计（手机号账号 + 跨租户），
数据隔离与身份模型最好一起改，避免两次改动互相返工。

已回退的内容（本地验证过 `mvn test` 57/57、`e2e` 64/64，回退后 51/51、64/64）：

| 项 | 处理 |
|---|---|
| `V99__tenant_isolation.sql`（3 表加 `org_id`） | 文件删除；MySQL 已 `DROP COLUMN` + 删除 flyway v99 记录，当前库回到 v98 |
| `OrgTenantHandler` + 租户拦截器 | 代码删除，`MybatisConfig` 恢复为仅分页拦截器 |
| 审计 `org_id` 写入/过滤 | 回退到原状（**已知缺口：审计仍跨租户可见**） |
| 文件 `{orgId}/` 目录 | 回退为 `yyyy-MM-dd/uuid` |
| `TenantIsolationTest`（6 条） | 删除 |

**保留的价值**：设计、例外表清单（`org` / `app_user` / `emission_factor`）、越权矩阵测试写法都已写入本文，
用户体系落地时可直接复用，不需要重新论证。

> 注意：审计日志跨租户可见这一条是真实存在的漏洞，建议在用户体系那一期一并修掉
> （随 `V100+` 迁移补 `audit_log.org_id`，与账号/租户改建同批上线）。

### 仍未做（按决策暂缓）

| 项 | 触发条件 |
|---|---|
| 租户开通/停用/配额 API 与平台端 | 有第二家客户正式开通时 |
| 独立库路由（P2） | 大客户要求私有化/独立备份时 |
