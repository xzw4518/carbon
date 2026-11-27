# 用户体系多租户方案 v2（含手机号账号与短信登录）

> **目录**
> §0 决策记录（16 条）｜§1 现状 ｜§2 架构 ｜§3 后端 ｜§4 数据库 ｜§5 前端 ｜§6 迁移 ｜§7 验收 ｜§8 风险 ｜§9 决策完成度
> 附录 A 第一轮补充（架构/后端/库/前端/安全/运维/文档缺口）
> 附录 B 状态机｜C 开通 SOP｜D 权限矩阵｜E 错误码表｜F 令牌与 CSRF
> 附录 G 第二轮补充（对代码审计出的爆炸半径与边界）
> 附录 H 第三轮补充（业务与运营视角 + 三场景走查）

> 日期：2026-11-25　状态：决策已定，待实施　范围：账号、租户成员关系、登录、角色、会话、管理页面
> 本版变更：账号标识改为**手机号**；新增**短信验证码登录**；账号可**跨租户**，角色按租户独立。

---

## 0. 决策记录（H1 已确认）

| # | 决策项 | 选择 |
|---|---|---|
| 1 | 账号与租户关系 | **允许跨租户**：一个账号可属于多个租户 |
| 2 | 新账号开通 | **管理员创建**（不发自助注册） |
| 3 | 密码策略 | **强策略 + 90 天过期**：≥12 位含大小写+符号，不得与前 3 次重复 |
| 4 | 角色模型 | **固定三角色**（admin / filler / viewer），本期不做自定义权限点 |
| 5 | 跨租户角色 | **每租户独立角色**：A 公司管理员、B 公司填报员 |
| 6 | 登录选租户 | **登录后选，记住上次**；顶栏可切换 |
| 7 | 令牌吊销 | **改密/停用即失效**（`token_version`），不建在线设备表 |
| 8 | 是否保留旧隔离代码 | **回退**（已完成，库回 v98） |
| 9 | 账号标识（新增） | **手机号**，全局唯一 |
| 10 | 登录方式（新增） | **密码登录 + 短信验证码登录**双通道 |
| 11 | 短信通道（新增） | **先做 SmsSender 接口 + 日志模拟**（固定码 123456），不接实名短信 |
| 12 | 用户名登录过渡（新增） | **不保留**：上线即只认手机号，现有账号补测试号 |
| 13 | 验证码防刷（新增） | **加图形验证码**：发送短信前必须通过图形校验 |
| 14 | 令牌存放（新增） | **HttpOnly + SameSite=Lax Cookie**，配 CSRF double-submit token |
| 15 | 手机号存储（新增） | **明文存储 + 全面脱敏**（列表/日志/导出），导出需二次确认并记审计 |
| 16 | 成员批量（新增） | **本期就做** CSV 导入与成员导出 |
| 17 | 流程角色（新增） | **本期加** `reviewer` 复核、`approver` 批准，并强制职责分离 |
| 18 | 组织层级（新增） | **本期加** 集团—工厂两级（`parent_org_id` / `org_level`） |
| 19 | 初始密码分发（新增） | **统一临时密码** + 首登强制改密（7 天未激活自动停用） |

---

## 1. 当前用户体系现状

### 1.1 已具备

`org` 租户表（含工商信息）、`app_user`（username 全局唯一、BCrypt、role、org_id、enabled）、
`POST /api/auth/login` 签发 JWT（sub/org/role，8h）、`@PreAuthorize` 三角色、
登录失败 5 次锁 15 分钟（内存）、`audit_log`、启动种入 admin/filler/viewer。

### 1.2 与目标模型的差距

| # | 现状 | 目标 |
|---|---|---|
| 1 | 账号 = username | 账号 = 手机号 |
| 2 | 账号只属一个租户（`app_user.org_id`） | 账号 ↔ 租户多对多（`user_tenant`），角色在关系上 |
| 3 | 只有密码登录 | 密码 + 短信验证码 |
| 4 | 无用户管理接口/页面 | 管理员可建号、授角色、停用、重置 |
| 5 | 无改密/重置 | 改密 + 短信重置 |
| 6 | 令牌不可吊销 | `token_version` |
| 7 | 无密码策略 | 强策略 + 90 天 + 历史不可复用 |
| 8 | 无跨租户切换 | 顶栏切换 + 记住上次 |

---

## 2. 架构设计

### 2.1 领域模型

```
账号 Account (app_user)          身份：手机号 + 密码 + 状态
   │ 1
   │
   │ N
成员关系 Membership (user_tenant)  授权：在某个租户里的角色与状态
   │ N
   │
   │ 1
租户 Tenant (org)                数据边界：所有业务表带 org_id
```

- **账号**只解决"你是谁"（手机号 + 凭据），不带租户属性。
- **成员关系**解决"你在哪家公司、是什么角色"，一个账号多条。
- **租户**解决"数据属于谁"。
- 三者分离后，跨租户、调岗、停用都只动一条关系记录，不动账号本身。

### 2.2 登录与令牌

```
① 手机号 + 密码  ─┐
② 手机号 + 验证码 ─┴→ 校验账号状态 → 查 memberships
                        ├─ 0 条 → 提示"未加入任何企业"
                        ├─ 1 条 → 直接签发 JWT
                        └─ N 条 → 返回临时票据 + 租户列表 → 用户选择 → 换正式 JWT
正式 JWT claims: { sub: userId, phone, org: 当前租户, role: 该租户角色, tv: tokenVersion }
```

- 记住上次：服务端记 `last_org_id`，前端也存；下次登录直接进上次租户（顶栏可切换）。
- 切换租户：`POST /api/auth/switch-tenant`，校验 membership 后重签 JWT，不重新认证。
- 临时票据：5 分钟、只能用于 `tenant/select`，避免"选租户"阶段拿到正式令牌。

### 2.3 短信验证码

| 项 | 设计 |
|---|---|
| 场景 | `login` 登录、`join` 确认加入企业、`reset` 重置密码、`bind` 换绑手机号 |
| 形式 | 6 位数字，5 分钟有效，一次性 |
| 存储 | `sms_code` 表（当前无 Redis），存 `code_hash`（sha256），不存明文 |
| 频控 | 同手机号 60 秒 1 条、每天 10 条；同 IP 每小时 20 条；错误 5 次作废 |
| 防枚举 | 无论手机号是否注册，接口都返回"已发送" |
| 通道 | `SmsSender` 接口：当前 `LogSmsSender`（固定码 123456，只打日志，不真发）；生产换成云厂商实现即可 |
| 图形验证码 | 发送短信前必须通过：`GET /api/auth/captcha` 取 4 位图形（SVG，2 分钟有效、一次性），`sms/send` 带上 `captchaId + captchaCode` |
| 清理 | 每日定时删除 7 天前的验证码与过期图形码记录 |

### 2.4 权限与会话

- 角色仍是三个，**存在成员关系上**：`user_tenant.role`。
- 后端 `@PreAuthorize` 照旧用 `ROLE_*`，JWT 里的 role 来自当前租户的 membership。
- `token_version`：改密、重置、停用、角色变更时 +1；JWT 校验时比对（30 秒缓存），旧令牌 401。
- 停用租户或成员关系 → 该租户下的令牌失效。

---

## 3. 后端设计

### 3.1 包结构

```
com.carbonbal.identity
├─ account/   AppUser（改造：phone 唯一）、AccountService、UserController(/api/users)
├─ tenant/    Org（扩列）、Membership、MembershipService、TenantController(/api/platform/tenants)
├─ auth/      AuthController（密码登录/短信登录/选租户/切换/改密/重置/me）
├─ sms/       SmsCode、SmsCodeService、SmsSender（Log / Aliyun 实现）
└─ session/   TokenVersionService
```

### 3.2 接口清单

**认证（公开）**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/auth/captcha` | 返回 `{captchaId, image(base64 svg)}`，2 分钟有效、一次性 |
| POST | `/api/auth/sms/send` | `{phone, scene, captchaId, captchaCode}` → 校验图形码后发送（业务结果恒返回"已发送"） |
| POST | `/api/auth/sms/login` | `{phone, code}` → 单租户直接发 token；多租户返回 `tempToken + tenants` |
| POST | `/api/auth/password/login` | `{phone, password}` → 同上 |
| POST | `/api/auth/tenant/select` | `{tempToken, orgId}` → 正式 token |
| POST | `/api/auth/password/reset` | `{phone, code, newPassword}` → 重置并 `tv+1` |

**认证（已登录）**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/auth/me` | 账号 + 所属租户列表 + 当前租户 + 角色 |
| POST | `/api/auth/switch-tenant` | `{orgId}` → 新 token |
| POST | `/api/auth/password/change` | `{oldPassword, newPassword}` → `tv+1`，要求重新登录 |
| POST | `/api/auth/logout` | 客户端清 token（本期无服务端会话） |

**账号与成员（租户管理员）**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/users?keyword=&role=&status=&page=&size=` | **本租户成员**列表（手机号、姓名、角色、状态、最近登录） |
| POST | `/api/users` | `{phone, displayName, role}`：手机号未注册则建账号（待激活），已注册则生成邀请 |
| PATCH | `/api/users/{membershipId}` | 改角色（不能改自己/最后一个 admin） |
| POST | `/api/users/{membershipId}/disable` `/enable` | 停用/启用成员，`tv+1` |
| POST | `/api/users/{membershipId}/reset-password` | 生成一次性密码或短信重置链接 |
| DELETE | `/api/users/{membershipId}` | 移出本租户（不删账号） |

**平台端（暂缓）**：`/api/platform/tenants` 开通、停用、配额、用量。

### 3.3 关键流程：管理员加人（防跨租户劫持）

```
管理员输入手机号 + 角色
  ├─ 手机号未注册 → 创建账号（status=pending，无密码）→ 生成邀请 → 用户短信登录后设为 active
  └─ 手机号已注册 → 只生成 pending 邀请 → 用户用该手机号登录，看到"XX 企业邀请你加入" → 确认
```
任何情况下，**不能直接把别人的手机号拉进自己的租户**；必须手机号持有人确认。
邀请状态：`pending → active / rejected / expired(7 天)`。

### 3.4 密码策略落地

| 规则 | 实现 |
|---|---|
| ≥12 位，含大写/小写/数字/符号 | `PasswordPolicy.validate()`，注册/改密/重置统一走 |
| 不得与前 3 次相同 | `password_history` 表存最近 3 次 bcrypt hash |
| 90 天过期 | `password_updated_at`，登录时判断，过期则只允许改密 |
| 首登/重置后强制改密 | `must_change_password` |

---

## 4. 数据库设计

### 4.1 账号表（改造 `app_user`）

```sql
ALTER TABLE app_user ADD COLUMN phone                VARCHAR(20) NULL;
UPDATE app_user SET phone = CONCAT('1380000000', id) WHERE phone IS NULL;  -- 过渡：给存量账号补测试号
ALTER TABLE app_user MODIFY COLUMN phone VARCHAR(20) NOT NULL;

ALTER TABLE app_user MODIFY COLUMN password_hash VARCHAR(100) NULL;        -- 短信登录用户可无密码
ALTER TABLE app_user ADD COLUMN status               VARCHAR(16) NOT NULL DEFAULT 'active';
ALTER TABLE app_user ADD COLUMN token_version        INT         NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN password_updated_at  DATETIME    NULL;
ALTER TABLE app_user ADD COLUMN last_org_id          BIGINT      NULL;
ALTER TABLE app_user ADD COLUMN last_login_at        DATETIME    NULL;
ALTER TABLE app_user ADD COLUMN last_login_ip        VARCHAR(64) NULL;
ALTER TABLE app_user ADD COLUMN version              INT         NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uk_user_phone ON app_user (phone);
-- 过渡期保留 username 登录，稳定后 DROP COLUMN username
```

### 4.2 成员关系（新增 `user_tenant`）

```sql
CREATE TABLE user_tenant (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  org_id     BIGINT      NOT NULL,
  role       VARCHAR(16) NOT NULL,              -- admin / filler / viewer
  status     VARCHAR(16) NOT NULL DEFAULT 'active', -- active / pending / disabled
  invited_by VARCHAR(64),
  created_at DATETIME    NOT NULL,
  joined_at  DATETIME    NULL,
  UNIQUE KEY uk_user_org (user_id, org_id),
  KEY idx_org_role (org_id, role, status)
);
```

迁移：把现有 `app_user(org_id, role)` 搬进 `user_tenant`（`status=active`），
`app_user.org_id/role` 保留一个版本做回滚兜底，之后删除。

### 4.3 短信验证码（新增 `sms_code`）

```sql
CREATE TABLE sms_code (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  phone      VARCHAR(20)  NOT NULL,
  scene      VARCHAR(16)  NOT NULL,   -- login / join / reset / bind
  code_hash  VARCHAR(64)  NOT NULL,
  expires_at DATETIME     NOT NULL,
  attempts   INT          NOT NULL DEFAULT 0,
  used_at    DATETIME     NULL,
  ip         VARCHAR(64),
  created_at DATETIME     NOT NULL,
  KEY idx_phone_scene (phone, scene, created_at),
  KEY idx_created (created_at)
);
```

### 4.4 图形验证码（新增 `captcha`）

```sql
CREATE TABLE captcha (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  captcha_id VARCHAR(64) NOT NULL,
  code_hash  VARCHAR(64) NOT NULL,      -- 存 sha256(小写答案)
  expires_at DATETIME    NOT NULL,      -- 2 分钟
  used_at    DATETIME    NULL,
  ip         VARCHAR(64),
  created_at DATETIME    NOT NULL,
  UNIQUE KEY uk_captcha (captcha_id)
);
```

### 4.5 密码历史（新增 `password_history`）

```sql
CREATE TABLE password_history (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT       NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  created_at DATETIME     NOT NULL,
  KEY idx_user (user_id, created_at)
);
```

### 4.6 租户扩列（`org`）

```sql
ALTER TABLE org ADD COLUMN status           VARCHAR(16) NOT NULL DEFAULT 'active';
ALTER TABLE org ADD COLUMN plan             VARCHAR(16) NOT NULL DEFAULT 'standard';
ALTER TABLE org ADD COLUMN seat_limit       INT         NOT NULL DEFAULT 20;
ALTER TABLE org ADD COLUMN expire_at        DATE        NULL;
ALTER TABLE org ADD COLUMN suspended_at     DATETIME    NULL;
```

### 4.7 顺便修掉审计缺口

用户体系改造同批加 `audit_log.org_id`（按 actor 反查回填），
这样账号/租户/成员的所有操作都能按租户审计——这条是上一轮回退后遗留的真实缺口。

---

## 5. 前端功能模块

### 5.1 登录页（改造）

```
┌──────────────────────────────┐
│  能碳管理平台                     │
│  ┌ 验证码登录 ┬ 密码登录 ┐    │   ← Tab 切换
│  手机号  [__________]        │
│  图形码  [____] [ ABC3 ]      │   ← 点击刷新，2 分钟有效
│  验证码  [______] [获取验证码] │   ← 60 秒倒计时，发送前校验图形码
│  [       登 录       ]       │
│  未开通？联系管理员           │
└──────────────────────────────┘
```

- 手机号做格式校验（11 位、1 开头），自动去空格与 `+86`。
- 错误提示分：验证码错误/已过期、账号已停用、企业已停用、失败次数过多。
- 登录成功后若多租户 → 弹「选择企业」层（公司名 + 角色），默认高亮上次进入的那家。

### 5.2 租户端新增模块

```
系统管理
├─ 账号管理（新）
│   列表：手机号 / 姓名 / 角色 / 状态 / 最近登录 / 操作
│   新增：手机号 + 姓名 + 角色 → 未注册建号、已注册发邀请
│   操作：改角色、重置密码、停用·启用、移出企业
│   状态：待确认（pending）/ 正常 / 已停用
├─ 操作日志（复用现有审计页）
个人中心（新）
├─ 手机号、姓名、所属企业（可切换）
├─ 修改密码（强策略校验提示）
└─ 退出登录
```

顶栏：显示「企业简称 · 姓名 · 角色」，点击可切换企业（多租户时）。

### 5.3 平台端（暂缓）

租户管理（开通/停用/配额/用量）、平台账号、全局审计。按决策第 8 条暂不做。

---

## 6. 迁移与过渡

| 步骤 | 内容 |
|---|---|
| 1 | 迁移加列 + `user_tenant` + 把现有 3 个账号搬成成员关系 |
| 2 | 存量账号补手机号（测试号 13800000001/2/3），并标记 `must_change_password` |
| 3 | **不保留用户名登录**：`/api/auth/login` 改为只认手机号，`username` 列保留一版用于回滚 |
| 4 | 短信通道用 `LogSmsSender`（固定码 123456，写日志），生产换实现即可 |
| 5 | 同步改造 `e2e.sh` 与无头脚本：登录改用测试手机号 + 密码 |

自动化影响：`e2e.sh`、无头浏览器脚本目前用 admin/Admin@2026!Pass 登录，改造后统一换
`13800000001` / `Admin@2026!Pass4!`（测试号，密码满足强策略）。这次改造要同批改脚本，否则门禁会红。

---

## 7. 验收标准

1. 管理员用手机号新增成员 → 该手机号短信登录 → 看到邀请 → 确认 → 进入本企业。
2. 同一手机号被两家企业邀请 → 登录后出现企业选择 → 切换后数据、菜单、角色随之变化。
3. 改密后，旧 JWT 立即 401；被移出企业的成员，其该企业 token 失效。
4. 密码不符合强策略（11 位/无符号）→ 400 且给出具体原因；连续 4 次历史密码重复 → 400。
5. 密码 90 天到期 → 登录后被强制跳改密页，其他接口 403。
6. 验证码：60 秒内重复发送 → 429；错误 5 次 → 作废；过期 → 400。
7. 租户 A 管理员看不到租户 B 的成员；非管理员调用 `/api/users` → 403。
8. 审计：账号/成员/租户操作都能按租户查到，且跨租户不可见。
9. 图形码：不传或传错 → 400，且短信不发送；同一 captchaId 用第二次 → 400。

---

## 8. 风险

| 风险 | 应对 |
|---|---|
| 短信成本与到达率 | 频控 + 只对已注册号码真实发送；供应商选有模板报备的云厂商 |
| 手机号被恶意枚举 | 恒返回"已发送"；发送前强制图形验证码；同 IP 每小时 20 条 |
| 免密登录的设备安全 | 敏感操作（改密、加人、移出成员）要求密码或二次验证码 |
| 跨租户邀请劫持 | 邀请必须由手机号持有人短信确认，不能直接拉人 |
| 存量账号无手机号 | 迁移补测试号并强制首登改密；legacy 登录过渡 |
| 90 天过期导致演示中断 | 演示账号单独豁免（`password_never_expire` 标记） |

---

## 9. 决策完成度

| 项 | 状态 |
|---|---|
| 账号标识、登录方式、跨租户、角色、密码策略、会话、租户选择 | ✅ 已确认（见 §0） |
| 短信通道、用户过渡、防刷 | ✅ 已确认：接口 + 模拟短信、直接切手机号、加图形验证码 |
| 遗留待办 | 生产接入真实短信时补：云厂商 AccessKey、签名与模板报备、发送结果回执 |

**结论：方案已可实施。** 建议按 §6 迁移顺序一次做完，验收按 §7 的 9 条。

---

# 附录 A · 方案补充清单（二次审阅）

> 以下是把方案对着"真上线"逐项过一遍后，发现还缺的内容。按 架构 / 后端 / 数据库 / 前端 / 安全 / 运维 / 文档 分组，
> 最后给出优先级。**标 ★ 的是不做就会在上线后出事的项。**

## A1. 架构补充

| # | 缺口 | 补充设计 |
|---|---|---|
| A1.1 ★ | 租户强制隔离被回退，用户体系上线后 `user_tenant` 仍是手写过滤 | 与用户体系同批恢复 `TenantLineInnerInterceptor`；例外表改为 `org`、`app_user`、`emission_factor`，**`user_tenant` 必须自动过滤**；同时补 `audit_log.org_id` |
| A1.2 ★ | 服务端拿不到"当前用户 id"，只有 username 字符串 | `CurrentUser` 增加 `userId()`（JWT `sub` 改为 userId）、`phone()`、`membership()`；业务表 `created_by` 继续存手机号，但审计增加 `actor_id` |
| A1.3 | 三套状态机没有定义 | 见附录 B：账号 `pending→active→disabled→locked`、成员 `pending→active→disabled→removed`、邀请 `pending→accepted/rejected/expired` |
| A1.4 ★ | 登录锁是单实例内存，多副本失效，且与账号模型不匹配 | 落库：`app_user.failed_count` + `locked_until`，按"手机号 + IP"双维度计数；成功登录清零 |
| A1.5 ★ | 令牌存 localStorage，XSS 可直接窃取 | **已定：HttpOnly + SameSite=Lax Cookie**。落地细节：① 登录成功 `Set-Cookie: cb_token=…; HttpOnly; SameSite=Lax; Path=/; Max-Age=28800; Secure(prod)`；② 同时下发 `csrf_token`（非 HttpOnly），前端写请求带 `X-CSRF-Token`，服务端 double-submit 校验；③ `JwtAuthFilter` 先读 Cookie，读不到再读 `Authorization: Bearer`（保留脚本与 e2e 兼容）；④ 退出登录需 `Clear-Site-Data` 或显式清除两个 Cookie |
| A1.6 | 定时任务以 `system` 身份运行，绕过成员状态 | `SystemAuth` 增加租户状态校验；成员被移出后任务不应再以该身份写入（目前任务不写业务数据，风险低，但规则要写死） |
| A1.7 | 没有平台端，第二家客户开通只能靠 DBA | 先定"开通 SOP 脚本"（附录 C）；平台端仍按决策暂缓，但不能没有 SOP |

## A2. 后端补充

| # | 缺口 | 补充接口 / 规则 |
|---|---|---|
| A2.1 ★ | 换绑手机号没有设计 | `POST /api/auth/phone/send-old` + `/send-new` + `/phone/rebind`：旧号验证码 + 新号验证码双验证；换绑后 `tv+1`，所有端重新登录 |
| A2.2 ★ | 邀请只有"生成"，没有重发/撤销/过期处理 | `POST /api/users/{membershipId}/resend-invite`、`DELETE /api/users/{membershipId}`（撤销 pending）；定时任务清理 7 天过期邀请 |
| A2.3 ★ | 最后一个管理员/自己的保护只写在文档里 | 服务层强制：不能停用/降级/移出自己；不能移除租户最后一个 `admin`（409 + 明确文案） |
| A2.4 ★ | 验证码并发消费 | 用 `UPDATE sms_code SET used_at=NOW() WHERE id=? AND used_at IS NULL` 的受影响行数判断，天然幂等，避免同一码用两次 |
| A2.5 | 成员批量处理 | `POST /api/users/import`（手机号,姓名,角色 CSV）+ `GET /api/users/export`；逐行独立提交，返回失败行 |
| A2.6 | 登录流水与风控 | `GET /api/users/{id}/login-logs`（管理员）、`GET /api/auth/login-logs`（本人最近 10 条） |
| A2.7 ★ | 错误码没有体系 | 统一：`AUTH_BAD_CREDENTIAL`、`AUTH_CODE_EXPIRED`、`AUTH_CODE_LIMIT`、`AUTH_CAPTCHA_INVALID`、`ACCOUNT_DISABLED`、`ACCOUNT_LOCKED`、`TENANT_SUSPENDED`、`MEMBER_PENDING`、`MEMBER_LAST_ADMIN`（HTTP 400/401/403/423/429 一一对应） |
| A2.8 | IDOR：所有 `{membershipId}` 路径未约束 | 一律 `require(orgId, membershipId)`，跨租户返回 404（不暴露存在性） |
| A2.9 | 密码历史校验顺序 | 先校验强度 → 再查历史 3 次 → 再落库；改密与重置共用同一方法 |
| A2.10 | 登录响应缺"密码快过期"提示 | `me` 与登录响应带 `passwordExpireInDays`，≤15 天前端显示提醒条 |
| A2.11 | 多端登录策略未定义 | 默认允许多端（手机+电脑），`tv` 只在改密/停用时集体失效；如需限制并发，P2 再加会话表 |
| A2.12 | 接口未做限流 | 登录/发码按 IP + 手机号限流（应用内计数器即可，不引入网关） |

## A3. 数据库补充

```sql
-- 账号：补状态、锁定、软删
ALTER TABLE app_user ADD COLUMN failed_count INT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN locked_until DATETIME NULL;
ALTER TABLE app_user ADD COLUMN deleted_at   DATETIME NULL;
ALTER TABLE app_user ADD COLUMN password_algo VARCHAR(16) NOT NULL DEFAULT 'bcrypt';

-- 登录流水（与 audit_log 分开：量大、只读、按时间清理）
CREATE TABLE login_log (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id     BIGINT NULL,                 -- 登录时可能还没确定租户
  user_id    BIGINT NULL,
  phone      VARCHAR(20) NOT NULL,
  result     VARCHAR(24) NOT NULL,        -- ok / bad_password / bad_code / disabled / locked / no_tenant
  ip         VARCHAR(64),
  user_agent VARCHAR(255),
  at         DATETIME NOT NULL,
  KEY idx_phone_at (phone, at),
  KEY idx_org_at (org_id, at)
);

-- 成员关系补字段
ALTER TABLE user_tenant ADD COLUMN employee_no VARCHAR(32) NULL;
ALTER TABLE user_tenant ADD COLUMN title       VARCHAR(64) NULL;
ALTER TABLE user_tenant ADD COLUMN removed_at  DATETIME NULL;

-- 验证码补发送回执，便于排障
ALTER TABLE sms_code ADD COLUMN provider_msg_id VARCHAR(64) NULL;
ALTER TABLE sms_code ADD COLUMN send_status     VARCHAR(16) NOT NULL DEFAULT 'sent';
ALTER TABLE sms_code ADD COLUMN error_msg       VARCHAR(255) NULL;

-- 图形码补尝试次数
ALTER TABLE captcha ADD COLUMN attempts INT NOT NULL DEFAULT 0;

-- 租户补运维短码与本地化
ALTER TABLE org ADD COLUMN tenant_code VARCHAR(16) NULL;
ALTER TABLE org ADD COLUMN timezone    VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai';
CREATE UNIQUE INDEX uk_org_tenant_code ON org (tenant_code);
```

| # | 缺口 | 说明 |
|---|---|---|
| A3.1 ★ | 手机号明文存储与导出 | **已定：库内明文 + 全面脱敏**。列表/日志/异常提示一律 `138****0001`；导出成员需二次确认并写审计；日志组件统一过滤 11 位手机号 |
| A3.2 | 迁移脚本无回滚 | 每个 `V1xx` 配 `docs/rollback/V1xx-down.sql`，并在预发演练一次 |
| A3.3 | 历史字段语义变化 | `created_by` 从 username 改为手机号后，旧数据保留 username；报表/审计需同时兼容两种值（按 `@` 或 11 位数字判断） |
| A3.4 | `password_history` 无唯一约束 | 加 `UNIQUE(user_id, password_hash)` 防重复插入 |
| A3.5 | 删除策略 | 账号不物理删；租户只停用；成员移出保留 `removed_at` 供审计 |

## A4. 前端补充

| # | 模块 | 补充点 |
|---|---|---|
| A4.1 ★ | 切换租户 | 切换后必须**清空本地快照并重拉 `/api/bootstrap`**，否则页面残留上一家的数据（现原型是全局 `DB` 覆盖，需显式 reset） |
| A4.2 ★ | 会话过期 | 统一拦截 401 → 清 token → 跳登录并带 `redirect`；401 不能只在单个接口里处理 |
| A4.3 ★ | 账号管理 | 待确认成员单独筛选 + 顶部红点；"复制初始密码"仅显示一次；重置/停用/移出都要二次确认；自己那一行禁用编辑 |
| A4.4 | 登录页 | 手机号 3-4-4 自动分段；验证码 6 格输入；图形码点击刷新（带 aria-label）；60 秒倒计时与"重新获取"；协议勾选；错误定位到字段 |
| A4.5 | 个人中心 | 手机号脱敏；换绑手机号双验证流程；密码强度实时条；90 天到期倒计时；"最近登录"列表 |
| A4.6 ★ | 批量（本期做） | 成员 CSV 导入（模板下载 + 失败行下载 + 逐行结果）、成员列表导出（二次确认 + 审计） |
| A4.7 | 状态与空态 | 企业停用、账号停用、无权限、无成员、邀请过期，五种状态各有明确文案与出口 |
| A4.8 | 顶栏 | 企业切换器（多租户才显示）、角色徽标、待确认成员红点（管理员） |
| A4.9 | 移动端 | 登录页按手机尺寸适配（当前管理端是桌面布局），验证码输入不遮挡键盘 |

## A5. 安全与合规

| # | 项 | 要求 |
|---|---|---|
| A5.1 ★ | 日志脱敏 | 手机号、验证码、密码、JWT 一律不打日志；异常栈里若含手机号需过滤 |
| A5.2 ★ | 撞库防护 | 密码错误统一提示"手机号或密码错误"，不区分账号是否存在；按手机号 + IP 双维度锁定 |
| A5.3 | 个人信息 | 手机号属于个人信息，需提供"注销账号"入口与导出；保留期写进隐私说明 |
| A5.4 | 敏感操作二次验证 | 改密、换绑、移出成员、导出成员 → 要求当前密码或短信码 |
| A5.5 | 密码哈希 | bcrypt cost 12；`password_algo` 字段支持后续平滑升级 |
| A5.6 | 前端安全 | 若改 Cookie 方案需 CSRF token + `SameSite=Lax`；加 `Content-Security-Policy` 头 |

## A6. 运维与交付

| # | 项 | 说明 |
|---|---|---|
| A6.1 ★ | 租户开通 SOP | 附录 C 的脚本模板：建 org → 建账号（手机号）→ 建 admin 成员 → 初始化主数据 |
| A6.2 | 短信可观测 | 发送量/成功率/失败原因看板；余额阈值告警（现在模拟通道，接实名后必须有） |
| A6.3 | 登录风控告警 | 单 IP 失败率突增、单账号短时间多地登录 → 告警（P2） |
| A6.4 | 数据迁移演练 | 在预发用生产数据副本跑一遍 V100 迁移 + 回滚，记录耗时与失败点 |

## A7. 文档缺的内容

| # | 缺 | 建议 |
|---|---|---|
| A7.1 | 状态机图 | 附录 B 三张（账号/成员/邀请） |
| A7.2 | 时序图 | 短信登录、邀请加入、切换租户、换绑手机号 四条 |
| A7.3 | 错误码表 | 见 A2.7，落成表格放 README |
| A7.4 | 权限矩阵 | 谁能调哪些接口（平台/租户 admin/filler/viewer/本人） |
| A7.5 | 字段字典 | 新表逐字段：类型、必填、默认、枚举、脱敏规则 |
| A7.6 | 测试用例清单 | 与 §7 验收对应到具体断言，避免"验收写了没测" |

## A8. 优先级与建议

| 级别 | 内容 | 理由 |
|---|---|---|
| **P0（编码前必须定）** | A1.1 租户隔离恢复、A1.2 `userId` 上下文、A1.4 登录锁落库、A1.5 令牌存储方式、A2.1 换绑、A2.3 管理员保护、A2.4 验证码原子消费、A2.7 错误码、A3.1 脱敏、A4.1 切租户刷新、A4.2 401 统一处理、A6.1 开通 SOP | 这些决定数据结构与安全边界，后补要返工 |
| **P1（首版要有）** | 邀请重发/撤销、**成员导入导出（已定为本期）**、登录流水、密码到期提醒、图形码无障碍、状态空态、回滚脚本、时序图/权限矩阵 | 影响可用性与可运维性 |
| **P2（可后置）** | 平台端租户管理、在线设备与踢下线、异地登录风控、部门/岗位、账号合并、手机号加密存储 | 依赖规模或客户要求 |

> 其中 **A1.5（JWT 存哪里）** 和 **A3.1（手机号脱敏/加密）** 是仅有的两处"改起来会牵动全局"的决策，
> 建议在开工前单独拍板；其余都可以按 P0 清单直接落。

---

# 附录 B · 状态机

**账号（app_user.status）**

```
           创建（管理员/邀请）
                   │
                   ▼
              pending ──首次登录/设密──▶ active ──管理员停用──▶ disabled
                   │                      │  ▲                    │
                   │                      │  └──────启用──────────┘
                   │              连续失败≥5次
                   │                      ▼
                   └────7天未激活──▶ expired   locked ──15分钟/管理员解锁──▶ active
                                                   │
                                                   └──注销──▶ deleted（软删，不可登录）
```

**成员关系（user_tenant.status）**

```
  邀请创建 ──▶ pending ──手机号持有人确认──▶ active ──管理员停用──▶ disabled
                 │                            │                      │
                 ├──拒绝/7天过期──▶ rejected    └──────移出───────────┴──▶ removed
                 └──管理员撤销──▶ removed
```

**邀请令牌（user_token）**

```
  issued ──▶ pending ──使用──▶ used
                ├──过期（7天）──▶ expired
                ├──重发（旧令牌作废）──▶ revoked + 新 pending
                └──撤销──▶ revoked
```

# 附录 C · 租户开通 SOP（暂无平台端时）

```sql
-- 1. 建租户（tenant_code 仅运维用，不参与登录）
INSERT INTO org (name, short_name, code, tenant_code, status, plan, seat_limit, created_at)
VALUES ('某某制造有限公司', '某某制造', 'ORG-2026-001', 'T001', 'active', 'standard', 20, NOW());

-- 2. 建管理员账号（手机号必填；初始密码用脚本生成，首次登录强制改）
INSERT INTO app_user (phone, password_hash, display_name, status, must_change_password,
                      token_version, password_updated_at, created_at)
VALUES ('13900000001', '<bcrypt>', '张管理', 'active', TRUE, 0, NOW(), NOW());

-- 3. 建立成员关系
INSERT INTO user_tenant (user_id, org_id, role, status, created_at, joined_at)
SELECT u.id, o.id, 'admin', 'active', NOW(), NOW()
FROM app_user u, org o WHERE u.phone='13900000001' AND o.tenant_code='T001';

-- 4. 初始化主数据（可选：设施、设备、因子）
--    按 docs/seed/tenant-template.sql 执行
```

开通后自检：用该手机号 + 初始密码登录 → 强制改密 → 能进首页 → 成员列表只有自己 →
审计里能看到 create 记录。

# 附录 D · 权限矩阵

| 能力 | platform_admin | 租户 admin | filler | viewer | 本人 |
|---|---|---|---|---|---|
| 租户开通/停用/配额 | ✅ | — | — | — | — |
| 成员列表 / 新增 / 改角色 / 停用 / 移出 | — | ✅（本租户） | — | — | — |
| 成员重置密码 | — | ✅ | — | — | — |
| 批量导入/导出成员 | — | ✅ | — | — | — |
| 审计日志 | 汇总（暂缓） | ✅ 本租户 | — | — | — |
| 登录日志 | — | ✅ 本租户 | — | — | ✅ 本人 |
| 切换租户 | — | ✅ | ✅ | ✅ | ✅ |
| 改密 / 换绑手机号 | — | ✅ | ✅ | ✅ | ✅ |
| 业务读写 | — | ✅ | ✅ 写 | 只读 | — |

# 附录 E · 错误码表（设计初稿）

> 已落地为字符串业务码，完整表与 HTTP 映射见 `platform/docs/error-codes.md`；本附录 code 以新表为准。

| code | HTTP | 场景 | 前端提示 |
|---|---|---|---|
| `AUTH_BAD_CREDENTIAL` | 401 | 手机号或密码错 | 手机号或密码错误 |
| `AUTH_CODE_INVALID` | 400 | 验证码错 | 验证码不正确 |
| `AUTH_CODE_EXPIRED` | 400 | 验证码过期/已用 | 验证码已失效，请重新获取 |
| `AUTH_CODE_LIMIT` | 429 | 60 秒内重复发送 / 当日超限 | 发送过于频繁，请稍后再试 |
| `AUTH_CAPTCHA_INVALID` | 400 | 图形码错/过期 | 图形验证码不正确 |
| `ACCOUNT_DISABLED` | 423 | 账号停用 | 账号已停用，请联系管理员 |
| `ACCOUNT_LOCKED` | 423 | 连续失败锁定 | 尝试次数过多，请 15 分钟后再试 |
| `TENANT_SUSPENDED` | 423 | 企业停用/到期 | 企业已停用或到期 |
| `MEMBER_PENDING` | 403 | 成员未确认邀请 | 请先确认加入企业 |
| `MEMBER_LAST_ADMIN` | 409 | 移除最后一个管理员 | 至少保留一名管理员 |
| `TENANT_NOT_FOUND` | 404 | 切换租户时无成员关系 | 你不在该企业中 |


---

# 附录 F · 令牌与 CSRF 落地细节（按决策 14）

| 项 | 设计 |
|---|---|
| 登录响应 | `Set-Cookie: cb_token=<jwt>; HttpOnly; SameSite=Lax; Path=/; Max-Age=28800`（prod 加 `Secure`）<br>`Set-Cookie: cb_csrf=<random>; SameSite=Lax; Path=/`（**非** HttpOnly） |
| 前端请求 | 所有写请求带 `X-CSRF-Token: <cb_csrf 的值>`；`fetch` 加 `credentials: 'same-origin'` |
| 服务端校验 | `CsrfFilter`：GET/HEAD/OPTIONS 放行；其余方法比对 `X-CSRF-Token` 与 `cb_csrf` Cookie，不一致 → 403 `CSRF_INVALID` |
| 兼容 | `JwtAuthFilter` 先读 Cookie，再读 `Authorization: Bearer`；`e2e.sh` 与无头脚本继续用 Bearer，不必改 |
| 退出 | 服务端返回 `Set-Cookie` 过期 + `Clear-Site-Data: "cookies"`；前端同时清内存状态 |
| 401 处理 | 统一拦截 → 清页面状态 → 跳登录（带 `redirect`），避免半登录状态 |
| 安全头 | `Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'`（原型内有内联样式，先留 inline，后续收敛） |

> 注意：改 Cookie 后，若有跨域前端（不同域名）需要 `SameSite=None; Secure` + CORS `allowCredentials`；
> 当前 `/app` 与 `/api` 同源，按上表即可。

---

# 附录 G · 第二轮补充（对着代码审计出来的）

> 附录 A–F 是"设计层面的缺项"，这一轮是"对着现有代码数出来的爆炸半径与边界场景"。
> 结论：**方案能落地，但有 6 处会返工的地方没写清**，已在下面补掉。

## G1. 标识迁移的爆炸半径（★ 最容易被低估）

| 事实（代码实测） | 影响 |
|---|---|
| `CurrentUser.username()` 在 **60 处**被调用 | 切到手机号后，这 60 处的返回值语义全变 |
| 28 张表里 **36 个 `created_by` + 15 个 `updated_by`** 列，存的是用户名字符串 | 历史值是 `admin`，新值是 `13900000001`，报表/审计会断层 |
| `DataSeeder` 硬编码 3 个账号、固定密码、`org_id=1` | 切换后要整体重写；`selectCount(null)` 在 app_user 忽略租户后无意义 |
| 前端 `paintUser()` 用 `displayName` 首字当头像，角色写死 | 切换租户后不会变 |

**补齐方案**：

1. 业务表新增 `created_by_id BIGINT NULL`、`updated_by_id BIGINT NULL`（不删旧列），写入时同时写 id 与手机号；
   历史行只回填 id（按旧 username 反查 app_user），查不到就留空。
2. 报表与审计统一按 `created_by_id` 聚合；展示时用 `coalesce(用户表.phone, created_by)` 兼容两种值。
3. 60 处 `username()` 分两批改：**先加 `CurrentUser.actor()`（返回手机号）**逐一替换，再删旧方法，避免一次性大改。
4. `DataSeeder` 改为读配置（`app.seed.admin-phone` 等），生产默认不种账号。

## G2. 账号级状态 vs 成员级状态（★ 语义没定清会互相打架）

两处都有「停用」，必须分开：

| 层级 | 字段 | 含义 | 效果 |
|---|---|---|---|
| 账号 | `app_user.status` | 账号本身是否可用（注销、风控封号） | 所有租户都登不上 |
| 账号 | `app_user.locked_until` | 密码错误锁定 | 同上，15 分钟后自解 |
| 成员 | `user_tenant.status` | 在**这家企业**的去留 | 只影响这家：登录时该企业不出现，token 里带该 org 的直接 403 |

规则：管理员在「账号管理」里点"停用"，改的是 `user_tenant.status`，**不动账号**；
只有平台侧风控才改 `app_user.status`。这条要写进接口注释，否则两边乱改。

## G3. 手机号唯一 + 软删的冲突（★ 会卡住真实运营）

`uk_user_phone` + 软删 → 手机号被永久占用，同一个人注销后无法再注册。

**方案**（选一）：
- **推荐**：注销时把 `phone` 改写成 `phone + '#deleted#' + timestamp`（保留原号码在 `phone_history` 表），唯一键不变；
- 备选：唯一键改 `(phone, status)`，`status='deleted'` 可多条——但会出现多条 deleted 冲突，需再加随机后缀。

配套新增：

```sql
CREATE TABLE phone_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  old_phone VARCHAR(20) NOT NULL,
  new_phone VARCHAR(20) NOT NULL,
  changed_by VARCHAR(64) NOT NULL,
  changed_at DATETIME NOT NULL,
  KEY idx_user (user_id, changed_at)
);
```

## G4. 离职与数据交接（★ 碳核算场景一定会遇到）

员工停用后，其创建的核算记录、报告、凭证仍在，`created_by` 指向停用账号。

补：
- 成员列表提供「交接」动作：`POST /api/users/{membershipId}/handover {toMembershipId}`，
  把该成员在**本租户**的 `created_by` 批量改成接手人（写审计，记录影响行数）；
- 报表页提供「按创建人筛选」，便于交接前核对；
- 交接不删原成员记录，历史审计仍可追溯。

## G5. 外部协作者账号（碳核查业务特有，方案完全没提）

第三方核查机构、供应商、审核老师需要**临时、受限**的访问。

补：`user_tenant` 增加

```sql
ALTER TABLE user_tenant ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'internal'; -- internal / external
ALTER TABLE user_tenant ADD COLUMN expires_at DATETIME NULL;                     -- 到期自动失效
```

- 外部账号角色固定 `viewer`（或专门的 `auditor`），只能看被授权的期间/报告；
- 到期由定时任务把 `status` 置 `disabled`，并 `token_version+1`；
- 外部账号不计入 `seat_limit`（或单独 `external_seat_limit`）。

## G6. 租户到期后的「只读模式」

现在只有 active/suspended 两态，到期直接拒登体验差、也不利于续费。

补：`status = expired` 时 **GET 放行、写操作 403 `TENANT_EXPIRED`**，
前端顶部显示"已到期，仅可查看"横幅 + 续费入口（平台端暂缓时先给联系方式）。

## G7. 数据范围先留位，避免以后加表

本期不做 SELF/DEPT，但要在表上留位，否则以后加列要改所有查询：

```sql
ALTER TABLE user_tenant ADD COLUMN data_scope VARCHAR(16) NOT NULL DEFAULT 'ORG'; -- ORG / SELF / DEPT
```

本期所有成员固定 `ORG`；后续按需开启，服务层读该值拼条件。

## G8. 前端已有实现与新规则的冲突（★ 现成的 bug）

| 现状 | 问题 |
|---|---|
| `assets/account.js` 改密弹窗规则：**8–20 位、字母+数字+符号三类** | 与决策「≥12 位、大小写+数字+符号四类」冲突 |
| 该弹窗保存只 `toast('密码修改成功')`，**没有接口**，文案还写着"原型演示：不会真正修改账号" | 用户以为改了密码，实际没改 |
| 登录后自动弹改密（`sessionStorage.cb_pwd_prompt`） | 应改为按后端 `passwordExpireInDays` 决定是否弹 |
| 头像/角色写死，`#avatar` 的 title 是"切换角色" | 多租户下应为"切换企业" |

补：改密弹窗接 `/api/auth/password/change`；规则统一由后端下发（`GET /api/auth/password-policy` 返回
长度/字符类/有效期），前端按返回渲染提示，避免两边各写一套。

## G9. 测试矩阵与并发用例（验收写了但没落到测法）

| 类型 | 用例 |
|---|---|
| 角色 × 接口 | 附录 D 的矩阵逐格断言（5 角色 × 12 接口 ≈ 60 条，可参数化） |
| 并发 | 同手机号并发建号只成功 1 个；同一验证码并发校验只成功 1 次；两人同时接受同一邀请只有 1 个 active |
| 越权 | 用 A 租户 token 改 B 租户 membershipId → 404；改自己角色 → 409；移除最后一个 admin → 409 |
| 安全 | CSRF 缺失头 → 403；验证码 6 次错 → 作废；XSS 输入被转义 |
| 迁移 | V100 全量迁移 → 回滚 → 再迁移，数据一致 |

## G10. 接口版本与文档

- 用户体系是破坏性变更（登录参数从 username 变 phone），建议接口加版本：`/api/v1/**`，
  旧 `/api/**` 保留一个版本做重定向；若不接受改路径，至少在登录接口保留 `phone` 与 `username` 二选一参数。
- 生成 OpenAPI（springdoc），前端据此对齐字段，避免接口改了页面没改。

## G11. 前端全局状态与路由守卫

- 新增 `session` 状态：`{userId, phone, tenants[], currentOrgId, role, permissions, passwordExpireInDays}`；
- 切换租户 → `session.currentOrgId` 变更 → **清空 `DB` 快照与所有页面局部 state** → 重新 `bootstrap`；
- 路由守卫：无 `currentOrgId` 跳登录；角色不足跳 403 页；按钮级用 `can('user:manage')` 而不是文本匹配；
- 现在 `api-client.lockWrites()` 用按钮文字正则删按钮，属于临时手段，用户体系上线时换成权限点驱动。

## G12. 可观测与自助解锁

- 登录失败率、验证码发送量/成功率、邀请过期数 → 日志指标；
- 账号被锁 15 分钟，用户只能干等：补管理员「解锁」按钮（`POST /api/users/{id}/unlock`，清 `locked_until` 与 `failed_count`）；
- 审计留存：`login_log` 保留 90 天、`sms_code` 7 天、`captcha` 1 天，定时清理。

## G13. 隐私与合规

- 登录页加"我已阅读并同意《用户协议》《隐私政策》"勾选（未勾选不能登录）；
- 提供「注销账号」入口（本人）与「导出我的数据」；
- 手机号、验证码、令牌禁止进日志与错误响应（统一在异常处理器里脱敏）。

## G14. 性能边界

- 每请求查 `token_version` + 租户状态 → 必须缓存（Caffeine，TTL 30s），否则 QPS 翻倍；
- `bootstrap` 在大租户（>5 万条读数）会变慢 → 用户体系上线时同步把 `/api/bootstrap` 拆成按页拉取（与"服务端分页"同一批做）。

## G15. 第二轮补充的优先级

| 级别 | 项 | 理由 |
|---|---|---|
| **P0（编码前定）** | G1 标识迁移方案、G2 账号/成员状态语义、G3 手机号复用、G8 改密弹窗冲突、G11 前端会话与守卫 | 决定表结构与接口签名，后补返工大 |
| **P1（首版要有）** | G4 交接、G6 到期只读、G9 测试矩阵、G12 解锁与留存、G13 协议勾选 | 影响真实运营与合规 |
| **P2（可后置）** | G5 外部协作者、G7 数据范围、G10 接口版本、G14 性能拆分 | 依赖业务规模或客户类型 |

---

# 附录 H · 第三轮补充（业务与运营视角）

> A–G 解决的是"用户体系自身对不对"，这一轮解决"放到这个业务里够不够用"。
> 结论：**还差 3 个业务刚需**（流程角色、组织层级、批量开通的密码分发），以及 4 个运营能力。

## H1. 流程角色：碳核算"编制—复核—批准"三岗（已定：本期加）

现方案只有 admin / filler / viewer。但碳核算的实际流程是：

```
填报员编制 → 复核人复核 → 负责人批准 → 锁定归档
```

`viewer` 是"只读"，不能"复核通过"；`admin` 是系统管理员，不等于业务负责人。
现在的 `accounting_period` 状态机（draft→calculated→submitted→locked）只有"提交"，
没有"谁批准"，`period_unlock` 表里 `requestedBy/approvedBy` 两个字段也允许同一个人填。

**补**：

| 项 | 设计 |
|---|---|
| 角色 | 增加 `reviewer`（复核）、`approver`（批准）；仍属固定角色，不做自定义 |
| 职责分离（SoD） | 编制人 ≠ 复核人；复核人 ≠ 批准人；解锁申请人 ≠ 批准人（服务层强制，409） |
| 数据落点 | `accounting_period` 增加 `submitted_by / reviewed_by / reviewed_at / approved_by / approved_at` |
| 前端 | 核算记录行按状态显示「复核」「批准」按钮，操作人不同才可见 |
| 验收 | 同一账号先提交再复核 → 409 `SOD_VIOLATION` |

**已定（决策 17）**：本期落地。角色变为 5 个：`admin / filler / reviewer / approver / viewer`；
核算状态机由 `draft→calculated→submitted→locked` 扩展为
`draft→calculated→submitted→reviewed→approved→locked`，未复核不得批准、未批准不得锁定。

## H2. 组织层级：集团—工厂（已定：本期加两级）

`org` 现在是平的，一家客户 = 一个租户 = 一套数据。但制造客户常见"集团 + 多工厂"：
集团要看汇总，工厂只看自己，人可能在多个工厂。

**补**（本期先做轻量版）：

```sql
ALTER TABLE org ADD COLUMN parent_org_id BIGINT NULL;   -- 集团/工厂两级即可
ALTER TABLE org ADD COLUMN org_level TINYINT NOT NULL DEFAULT 1;
```

- 数据边界仍是"工厂"（`org_id` 不变），集团账号通过 `user_tenant` 挂到多个工厂；
- 集团汇总页按 `parent_org_id` 聚合（P2 再做）；
- 若客户没有集团诉求，此列留空即可，不增加复杂度。
- **已定（决策 18）**：本期建列并写入开通 SOP；集团汇总页放 P2，但数据模型先就位，避免以后改聚合查询。

## H3. 批量开通的初始密码分发（已定：统一临时密码）

决策是"管理员创建账号 + 首登强制改密"。单个没问题，但**一次开 30 人**时：
管理员拿到 30 个初始密码，怎么安全地发给 30 个人？聊天工具群发显然不行。

**补**（三选一，建议组合）：

| 方案 | 说明 |
|---|---|
| A. 批量导入时导出"激活码清单" | 每个手机号一行：手机号 + 一次性激活码 + 有效期 7 天；管理员线下分发，用户首登用"激活码 + 新密码"激活 |
| B. 短信邀请 | 导入后逐条发邀请短信（含短链），用户点开设密码；受短信通道限制（当前是模拟） |
| C. 统一初始密码 + 强制改密 | 最省事但最不安全，仅限内网/演示 |

**已定（决策 19）：C 统一临时密码**。为了把风险压住，配套 5 条硬约束：

| 约束 | 实现 |
|---|---|
| 临时密码按批生成 | 每次导入生成一个共享临时密码（12 位随机），随导入结果一次性展示/导出，不落库明文（存 bcrypt） |
| 首登必须改密 | `must_change_password=true`，未改密前除改密接口外全部 403 `MUST_CHANGE_PASSWORD` |
| 临时密码有效期 7 天 | 新增 `app_user.temp_password_expires_at`；超期未登录 → 自动 `disabled`，需管理员重置 |
| 改密即失效 | 改密后清 `temp_password_expires_at`，`token_version+1` |
| 可追溯 | 导入与重置都写审计：谁、什么时间、为哪些手机号生成了临时密码 |

```sql
ALTER TABLE app_user ADD COLUMN temp_password_expires_at DATETIME NULL;
```

> 风险提示（已接受）：统一临时密码一旦在群里扩散，存在"被人抢先登录并改密"的可能。
> 缓解：7 天有效期 + 管理员可在成员列表看到"未激活"状态 + 登录成功给管理员一条站内提醒。

## H4. 外部账号的授权范围（G5 的落地细化）

G5 只加了 `kind/expires_at`，但"只看被授权的报告"需要一张授权表：

```sql
CREATE TABLE external_grant (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_tenant_id BIGINT      NOT NULL,
  scope_type     VARCHAR(16) NOT NULL,   -- period / report / footprint
  ref_id         BIGINT      NOT NULL,
  granted_by     VARCHAR(64) NOT NULL,
  expires_at     DATETIME    NULL,
  created_at     DATETIME    NOT NULL,
  UNIQUE KEY uk_grant (user_tenant_id, scope_type, ref_id)
);
```

查询侧：外部账号的列表/详情接口统一叠加 `exists(select 1 from external_grant …)` 条件。

## H5. 因子库的归属与维护权限

`emission_factor` 里 national/local 是全局（`org_id` 为空），customer 是租户私有。
用户体系必须回答：**谁有权新增/审核国家级因子？**

**补**：

| 角色 | 权限 |
|---|---|
| platform_admin（暂缓） | 维护 national/local 因子、审核 |
| 租户 admin | 只能维护本租户的 `customer` 因子，不能改 national |
| 现有接口 | `POST /api/factors` 目前任何登录用户都能调 → 需加 `@PreAuthorize` 与 scope 校验 |

## H6. 审计的可读性与防篡改

- **可读性**：现在 `before_json/after_json` 直接存实体 JSON，业务看不懂。补 `action_label`（中文动作名）
  与字段中文名映射（前端渲染时用字典翻译），审计页显示"张管理 修改了 产值 2026-11 的产值：100→120"。
- **防篡改**：`audit_log` 增加 `prev_hash`、`row_hash`（sha256(prev_hash + 本条内容)），
  导出或定时任务校验链完整；等保/核查场景会问"审计能不能改"。

## H7. 单租户数据恢复（共享表模式的代价）

共享表下，某个租户误删数据只能整库恢复 → 会影响所有租户。

**补**：业务表统一软删（已有 `disabled`），并加"回收站"页面（管理员可恢复 30 天内删除的记录）；
超过 30 天才物理清理。这是共享表模式必须补的运维能力。

## H8. 国内 B 端的登录习惯：企业微信/钉钉

短信之外，客户更常问"能不能企业微信扫码登录"。

**补**（P2）：抽象 `IdentityProvider`（local / sms / wecom / dingtalk / oidc），
新增 `user_identity` 表（user_id, provider, external_id, union_id），
登录回调按 `external_id` 找到账号 → 走同一套"选租户 → 签 token"。方案里预留，不实现。

## H9. 权限复核与休眠账号（安全运营）

- **权限复核**：每季度导出「成员—角色—最近登录—数据量」清单给客户确认，回收多余权限；
  接口 `GET /api/users/access-review` 导出 CSV。
- **休眠账号**：90 天未登录且非 admin → 自动停用并发提醒；定时任务实现。
- 这两条是等保与甲方安全部门常查项。

## H10. 前端体验补齐（第三轮）

| # | 项 | 说明 |
|---|---|---|
| 1 | 首次登录引导 | 设密码 → 补姓名 → 进首页；三步，可跳过 |
| 2 | 邀请落地页 | 短信/链接打开后：显示"XX 企业邀请你"→ 输验证码 → 确认 |
| 3 | 会话到期提醒 | 剩余 5 分钟弹提示，可"继续使用"（静默续期）或"退出" |
| 4 | 多标签同步 | 一个标签退出/切租户，其他标签通过 `storage` 事件同步登出/刷新 |
| 5 | 水印 | 报告预览/导出加"手机号 + 时间"水印，防外泄（核查机构场景） |
| 6 | 移动端 | 短信登录 + 验证码输入是手机主场景，登录页必须适配小屏与键盘 |

## H11. 三场景走查（验证方案是否真的够用）

**场景 1：新客户开通 30 人（2 个厂区，1 个财务只读）**
建 org（T002）→ 批量导入 30 个手机号 + 角色 → 导出激活码清单分发 → 用户激活并改密 →
管理员看到 30 个 active。**走查发现**：缺 H3（密码分发）、缺 org 层级（2 个厂区若独立核算需 H2）；
批量导入需支持"角色"列（已在 A4.6）。

**场景 2：员工离职交接**
停用成员（`user_tenant.status=disabled`，token 失效）→ 交接其创建数据给接手人 →
审计留痕 → 3 个月后回收站清理。**走查发现**：缺 G4 的交接接口粒度定义（按表/按时间），
建议首版"全量交接本租户内该成员创建的全部业务数据"，简单可控。

**场景 3：核查机构临时访问**
创建外部成员（`kind=external`，`expires_at=+30d`，角色 auditor）→ 授权 2026 年度报告 →
对方登录只看得到被授权内容 → 到期自动失效 + token 失效。**走查发现**：
缺 H4 的 `external_grant` 与"授权"页面；缺水印（H10-5）。

## H12. 第三轮补充的优先级

| 级别 | 项 | 理由 |
|---|---|---|
| **P0（已拍板，纳入本期）** | H1 流程角色与职责分离、H2 集团层级、H3 统一临时密码、H5 因子维护权限 | 影响角色模型、表结构与接口签名 |
| **P1（首版应有）** | H4 外部授权表、H6 审计可读性与哈希链、H7 回收站、H9 权限复核/休眠 | 影响真实运营与合规检查 |
| **P2（可后置）** | H8 企业微信/钉钉、H10-3/4/5/6 体验项、集团汇总页 | 依赖客户类型与规模 |
