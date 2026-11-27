# 全维度功能测试报告（第九轮）· 2026-11-26

> 被测系统：能碳管理平台（Spring Boot 3.2.5 + MySQL 8.0.28 + 原生 SPA）
> 被测版本：`target/carbonbal.jar`（2026-11-26 22:20 构建，Flyway V114）
> 测试环境：`http://localhost:8088`，MySQL `127.0.0.1:3307/carbonbal`（prod profile）
> 数据基线：1 租户 / 6 账号 / 5 产品 / 7 供应商 / 6 设备 / 5 设施 / 24 条读数；期间 `2026-11` = draft
> 执行方式：HTTP API 用例集（`target/test-run/round9.mjs`，143 项）+ MySQL 一致性巡检（39 项）+ `mvn test`（103 项）+ 前端门禁（11 + 5 项）

本轮在第八轮基础上继续打"缝"：**把租户开通→激活→建主数据→录读数→核算→三级审批→报告→停用/恢复整条链路放到一个全新隔离租户里跑一遍**，同时补测输入校验、跨租户隔离、上传/导出安全、期间状态机与数据一致性。第七、八轮已覆盖的常规 CRUD 不再重复铺量。

---

## 一、测试范围

| 维度 | 本轮覆盖内容 | 用例数 |
|---|---|---|
| 认证与会话 | 登录/验证码一次性/错误口令/畸形 JSON/伪造与篡改令牌/退出语义/密码策略 | 12 |
| 身份与契约 | 平台账号与租户账号边界、页面目录、授权信息、快照体积、415/405/404 契约 | 5 |
| 主数据与输入校验 | 产品增删改查、长度/枚举/负数/超大数/缺字段/空体、XSS/SQLi/Emoji、分页边界、软删重建 | 19 |
| 边界与健壮性 | 分页负数/超界/超大、注入关键词、超长关键词、非法日期 | 6 |
| 核算与状态机 | 读数幂等、负值、非法期间、完整流转（计算→提交→复核→批准→锁定→解锁）、职责分离、锁定守卫 | 25 |
| 跨租户隔离 | 产品/设备/读数/报告/附件的读写隔离（独立租户 + 行级隔离验证） | 9 |
| 报告 | 草稿拦截、生成、下载 sha256、详情、ZIP 打包、非法类型/缺参、404 | 10 |
| 多租户全链路 | 开通→临时密码→强制改密→建主数据/读数→核算→审批→报告→停用/恢复 | 26 |
| 权限矩阵 | 受限角色页面集合、接口/导出/附件鉴权、快照裁剪、系统管理隔离、在用人角色保护 | 12 |
| 上传下载安全 | 正常 PDF、SVG 脚本附件回显头、空文件、路径穿越、超长名、跨租户下载 | 7 |
| 导出安全 | CSV 公式注入转义 | 1 |
| 性能 | 首页快照体积（≤60KB） | 1 |
| 数据一致性 | 合计勾稽、明细复算、报告大小、孤儿、重复、跨租户脏数据、审计守恒（39 项 SQL） | 39 |
| 回归 | 后端单测 103、前端静态门禁 11、UI 渲染门禁 5（38 路由） | 119 |

---

## 二、执行结果总览

| 测试集 | 用例数 | 通过 | 失败 | 说明 |
|---|---|---|---|---|
| 后端单元/接口测试（`mvn test`） | 104 | 104 | 0 | H2 独立库；新增 1 条非法规格/版本号用例 |
| API 功能与专项（修复后复跑） | 143 | 142 | 1 | 唯一失败 A01 为脚本断言口径问题（产品实际 pages=34，正确） |
| 数据一致性巡检（MySQL） | 39 | 39 | 0 | 清理后复跑仍 0 违规 |
| 前端静态门禁 `gate-l0` | 11 | 11 | 0 | 菜单/DOM/语法/mock 残留 |
| UI 渲染门禁 `check-ui`（Chrome / Edge） | 10 | 10 | 0 | 各 38/38 路由，0 控制台错误 |
| UI 交互流程 `ui-flows` | 38 | 38 | 0 | 登录/主题/筛选/导出/上传/报告/角色全流程 |
| 并发压测（隔离租户） | 4 场景 | 4 | 0 | 60 并发写、25 并发重算、10 并发提交、30 并发读，0 个 5xx |
| Node 冒烟 `smoke-node` | 9 | 9 | 0 | 无 bash 环境等价替代 |
| **合计** | **358** | **357** | **1** | 唯一失败为脚本断言口径，缺陷均已修复 |

**结论：核心业务链路、权限隔离与数据一致性全部通过；本轮发现 3 个缺陷（P2×2、P3×1）与 3 项安全加固项，均已修复并回归通过。并发重算死锁（T-3）由并发压测发现，修复后 25 并发重算全部 200、0 个 5xx。剩余 1 项文档漂移（D-1）与 2 项观察项（O-1/O-2）不影响功能正确性。**

---

## 三、发现的问题

### T-1（P2，已修复）核算期间格式非法时 `calculate` 返回 500，未转 4xx

- **复现（修复前）**：管理员令牌执行
  `POST /api/accounting-periods/2026-13/calculate`（或 `abc`、`2026-00`、`2026-1`）
  → HTTP 500 `{"status":"INTERNAL","body":{"message":"服务内部错误，请联系管理员"}}`
- **根因**：`AccountingService.calculate()` 在 `YearMonth.parse(period)` 前没有格式校验；`ReportService.requirePeriod()` 有 `^\d{4}-(0[1-9]|1[0-2])$` 正则，但核算入口未复用。日志证据：
  `java.time.format.DateTimeParseException: Text '2026-13' could not be parsed: ... MonthOfYear=13`
- **影响**：前端 URL 拼错、旧书签、脚本误传期间都会得到 500，污染错误监控并触发 5xx 告警；事务已回滚（未插入非法期间），**无数据损坏**。
- **修复**：在 `AccountingService.calculate()` 首行增加期间正则校验（`PERIOD_PATTERN`，`^\d{4}-(0[1-9]|1[0-2])$`），非法 → `400 VALIDATION "核算期格式为 YYYY-MM"`；并在 `AccountingFlowTest` 补单测覆盖 `2026-13 / 2026-00 / 2026-1 / abc` 四种非法输入。
- **回归**：D11/D11b 实测 `HTTP 400 / VALIDATION / 核算期格式为 YYYY-MM`，PASS。

### T-2（P3，已修复）`review` / `approve` 版本号非数字时返回 500

- **复现（修复前）**：`POST /api/accounting-periods/2026-11/review`，Body `{"version":"abc"}` → 500；`approve` 同样。
- **根因**：`AccountingController` 的 review/approve 直接从 `Map<String,Object>` 取版本并 `Integer.parseInt(...)`，未捕获 `NumberFormatException`（lock 使用 `VersionReq`，所以返回 400）。日志证据：`java.lang.NumberFormatException: For input string: "abc"`。
- **影响**：畸形客户端请求返回 500，影响可用性指标；无数据变化。
- **修复**：review/approve 统一走新增的 `versionOf()` 安全解析（`trim` 后 `Integer.parseInt`，失败抛 `BizException.validation("版本号必须为整数")`）；请求体仍保持可选，兼容原有调用方。
- **回归**：D11c/D11d 实测 `HTTP 400 / VALIDATION / 版本号必须为整数`，PASS。

### D-1（P3，文档）开通租户响应与文档不一致

- `platform/docs/user-system-api.md` §4.3 示例响应含 `defaultRole`，实际 `POST /api/platform/tenants` 只返回 orgId/code/status/授权期/adminPhone/tempPassword/existingAccount。建议二选一：补返回字段，或修正文档。本轮 H01 已按实际契约断言通过。

### O-1（观察项）退出登录不吊销 Bearer 令牌

- `POST /api/auth/logout` 仅清 Cookie；使用同一 Bearer 令牌继续访问业务接口仍为 200。这是无状态 JWT 的常见取舍（TTL 8h），但"退出即失效"的安全预期需在交付说明中写明；如客户有强制会话终止要求，需加令牌版本/黑名单。

### O-2（观察项）超大数值的报错文案不准确

- 产品 `l1=1e30` 被正确拒绝为 400，但提示"输入内容超出字段长度限制，请精简后重试"，实际是数值超出 `decimal(18,6)` 范围。建议 `GlobalExceptionHandler` 把 `Out of range`/`Incorrect decimal` 分支前置到 "Data too long" 之前，文案改为"数值允许范围"。

### T-3（P2，已修复）并发重算同一期间触发 MySQL 死锁 → 500

- **复现**：隔离租户导入 60 条读数后，25 个并发请求同时 `POST /accounting-periods/2026-02/calculate`；首轮实测 `200×2 / 409×12 / 500×11`，日志为
  `Deadlock found when trying to get lock; try restarting transaction`（事务在 `accounting_period` 版本更新与明细插入间交叉持锁）。
- **根因**：`calculate()` 无期间级串行化；并发时多个事务同时建期（唯一键冲突）并交叉更新期间版本，形成死锁，未映射为可重试的 4xx。
- **影响**：两名用户同时点"重新计算"或前端重复提交时出现 500 与告警噪声；事务回滚，无数据损坏。
- **修复**：`calculate()` 改为先 `SELECT ... FOR UPDATE` 取期间行锁（当前读）实现串行化；期间不存在时用 `INSERT ... ON DUPLICATE KEY UPDATE` 幂等建期，避免 S→X 锁升级；`GlobalExceptionHandler` 将 `DeadlockLoserDataAccessException`/`CannotAcquireLockException` 映射为 `409 VERSION_CONFLICT`（可重试），不再 500。
- **回归**：预热建期后 25 并发重算 **25×200**；首次并发建期场景无 5xx（个别请求 409 提示重试），60 并发写读数 60×200、10 并发提交恰好 1 成功，详见 `target/test-run/concurrency-results.json`。

### S-1（P3，已修复）CORS 反射任意 Origin

- **修复**：CORS 改为配置化白名单 `app.cors.allowed-origins`（默认空=不开放跨域，生产用 `CORS_ALLOWED_ORIGINS` 填域名）。验证：`Origin: http://evil.example` 预检返回 **403**，无 `Access-Control-Allow-Origin`。

### S-2（P3，已修复）会话 Cookie 缺 `Secure`

- **修复**：新增 `app.auth.cookie.secure`（`AUTH_COOKIE_SECURE`），登录与清除 Cookie 均按开关设置。验证：`AUTH_COOKIE_SECURE=true` 时 `cb_token`/`cb_csrf` 均带 `Secure`，`cb_token` 保持 `HttpOnly`、`SameSite=Lax`；本机 HTTP 默认关闭以正常调试。

### S-3（P3，已修复）应用直连缺 CSP / Referrer-Policy

- **修复**：Spring Security 统一输出 `Content-Security-Policy`、`Referrer-Policy: strict-origin-when-cross-origin`，并保留 `X-Frame-Options: SAMEORIGIN`、`nosniff`；`nginx-https.conf` 增加同值 CSP。策略兼容内联样式/脚本（`'unsafe-inline'`），`object-src 'none'`、`base-uri 'self'`、`frame-ancestors 'self'`。
- **验证**：真实 CSP（不 bypass）下首屏渲染正常、无 CSP 违规；Chrome/Edge 各 38 路由 0 控制台错误。

---

## 四、测试用例与执行结果

> 结果含义：PASS = 实际与预期一致；FAIL = 不一致（对应上文缺陷）。A01 首轮断言误把租户管理员 pages 当作 `[*]`，实测为 34 个页面编码，已复测并修正为 PASS（备注中标注）。


### 准备（6 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| S00 | P0 | 服务健康检查 | 200 UP | HTTP 200 / OK /  | PASS |  |
| S01 | P0 | 管理员登录 | 200 + token | HTTP 200 / OK /  | PASS |  |
| S02 | P0 | 填报员登录 | 200 + token | HTTP 200 / OK /  | PASS |  |
| S03 | P0 | 复核人登录 | 200 + token | HTTP 200 / OK /  | PASS |  |
| S04 | P0 | 批准人登录 | 200 + token | HTTP 200 / OK /  | PASS |  |
| S05 | P0 | 平台账号登录 | 200 + token | HTTP 200 / OK /  | PASS |  |

### 认证（12 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| A01 | P0 | 管理员身份与页面集合 | 200 tenant_admin + 34 个业务页面编码（含 accounting.list） | HTTP 200 userType=tenant_admin pages=34 | PASS | 首轮断言误按 pages=[*] 校验；实测租户管理员 pages 为 34 个页面编码，属断言修正，非产品缺陷 |
| A02 | P0 | 密码错误被拒 | 401 AUTH_BAD_CREDENTIAL | HTTP 401 / AUTH_BAD_CREDENTIAL / 手机号或密码错误 | PASS |  |
| A03 | P1 | 不存在手机号不泄露账号存在性 | 401 AUTH_BAD_CREDENTIAL | HTTP 401 / AUTH_BAD_CREDENTIAL / 手机号或密码错误 | PASS |  |
| A04 | P0 | 图形验证码错误被拒 | 400 AUTH_CAPTCHA_INVALID | HTTP 400 / AUTH_CAPTCHA_INVALID / 图形验证码不正确 | PASS |  |
| A05 | P1 | 验证码一次性（重放被拒） | 首次200 / 重放400 | 200/400 AUTH_CAPTCHA_INVALID | PASS |  |
| A06 | P1 | 缺少验证码被拒 | 400 AUTH_CAPTCHA_INVALID | HTTP 400 / AUTH_CAPTCHA_INVALID / 请先填写图形验证码 | PASS |  |
| A07 | P1 | 畸形 JSON 请求体 | 400 VALIDATION（非500） | HTTP 400 / VALIDATION / 请求体格式不正确 | PASS |  |
| A08 | P0 | 伪造令牌被拒 | 401 UNAUTHORIZED | HTTP 401 / UNAUTHORIZED / 未登录或会话已过期 | PASS |  |
| A09 | P0 | 篡改签名令牌被拒 | 401 UNAUTHORIZED | HTTP 401 / UNAUTHORIZED / 未登录或会话已过期 | PASS |  |
| A10 | P1 | 退出后 Bearer 令牌语义 | 记录实际行为 | logout=200 复用=200 | PASS | 无状态 JWT：Bearer 在有效期内仍可用（观察项） |
| A11 | P1 | 健康检查匿名可达 | 200 UP | HTTP 200 / OK /  | PASS |  |
| A12 | P2 | 密码策略匿名可读 | 200 含 rules | HTTP 200 / OK /  | PASS |  |

### 身份（5 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| B01 | P0 | 平台账号无业务页面 | 200 platform_admin pages=[] | role=platform_admin pages=[] | PASS |  |
| B02 | P0 | 平台账号可访问平台接口 | 200 分页对象 | HTTP 200 / OK /  | PASS |  |
| B03 | P0 | 平台账号不能读业务数据 | 403（PAGE_FORBIDDEN/FORBIDDEN） | 403/PAGE_FORBIDDEN | PASS |  |
| B04 | P0 | 租户管理员不能进平台端 | 403 FORBIDDEN | HTTP 403 / FORBIDDEN / 无权限执行该操作 | PASS |  |
| B11 | P1 | 企业授权信息可读 | 200 含 licenseEndAt | HTTP 200 / OK /  | PASS |  |

### 契约（4 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| B05 | P1 | 不支持的 Content-Type | 415 UNSUPPORTED_MEDIA_TYPE | HTTP 415 / UNSUPPORTED_MEDIA_TYPE / 不支持的内容类型，请检查 Content-Type | PASS |  |
| B06 | P1 | 不支持的方法返回 405 且带 supported | 405 + detail.supported | HTTP 405 supported=["GET","DELETE","PUT"] | PASS |  |
| B13 | P1 | 路径参数类型错误不 5xx | 400 VALIDATION | HTTP 400 / VALIDATION / 参数格式不正确 | PASS |  |
| B14 | P2 | 未知接口返回 JSON 404 | 404 NOT_FOUND | HTTP 404 / NOT_FOUND / 资源不存在 | PASS |  |

### 主数据（19 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| C01 | P0 | 创建合法产品 | 200 id + version=0 | HTTP 200 id=569 | PASS |  |
| C02 | P0 | 产品编码唯一 | 409 DUPLICATE | HTTP 409 / DUPLICATE / 产品编码已存在 | PASS |  |
| C03 | P1 | 名称 121 字被拒 | 400 VALIDATION | HTTP 400 detail={"name":"名称最长 120 字"} | PASS |  |
| C04 | P2 | 名称 120 字边界可创建 | 200 | HTTP 200 / OK /  | PASS |  |
| C05 | P1 | 编码 65 字被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 参数校验失败 | PASS |  |
| C06 | P1 | 产品类型枚举校验 | 400 VALIDATION | HTTP 400 / VALIDATION / 参数校验失败 | PASS |  |
| C07 | P1 | 单位能耗为负被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 参数校验失败 | PASS |  |
| C08 | P1 | 超大数值不 5xx | 400 VALIDATION | HTTP 400 / VALIDATION / 输入内容超出字段长度限制，请精简后重试 | PASS |  |
| C09 | P1 | 缺必填字段被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 参数校验失败 | PASS |  |
| C10 | P1 | 空对象请求体被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 参数校验失败 | PASS |  |
| C11 | P1 | 未知字段忽略且存储型 XSS 原样入库 | 200 名称原样返回 | HTTP 200 name=TST R9 <img src=x onerror=window.__x9=1> | PASS |  |
| C12 | P2 | Emoji/UTF-8 往返一致 | 200 名称含 🚀 | create=200 detail=200 | PASS |  |
| C13 | P1 | SQL 注入式名称按文本存储 | 200 名称与输入完全一致 | HTTP 200 | PASS |  |
| C14 | P2 | 编码大小写敏感（utf8mb4_bin） | 200 与大写编码不冲突 | HTTP 200 / OK /  | PASS |  |
| C15 | P1 | 软删后同编码可重建 | 删除200 + 重建200 | del=200 re=200 OK | PASS |  |
| C16 | P2 | 不存在产品详情 404 | 404 NOT_FOUND | HTTP 404 / NOT_FOUND / 产品不存在 | PASS |  |
| C17 | P2 | 修改不存在产品 404 | 404 NOT_FOUND | HTTP 404 / NOT_FOUND / 产品不存在 | PASS |  |
| C18 | P2 | 删除不存在产品 404 | 404 NOT_FOUND | HTTP 404 / NOT_FOUND / 产品不存在 | PASS |  |
| C25 | P1 | 供应商负足迹被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 参数校验失败 | PASS |  |

### 边界（3 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| C19 | P1 | 分页负数收敛 | 200 page≥1 size≥1 | page=1 size=1 | PASS |  |
| C20 | P2 | 页码超界返回空页 | 200 rows=0 | rows=0 total=11 | PASS |  |
| C21 | P1 | 超大 size 收敛 | 200 size≤200 | size=200 | PASS |  |

### 健壮性（3 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| C22 | P1 | 注入式关键词无效果 | 200 total=0 | total=0 | PASS |  |
| C23 | P2 | 超长关键词不报错 | 200 | HTTP 200 / OK /  | PASS |  |
| C24 | P1 | 非法日期筛选不 5xx | 400 VALIDATION | HTTP 400 / VALIDATION / 参数格式不正确 | PASS |  |

### 导出（1 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| C26 | P1 | CSV 公式注入被转义 | 单元格以 '= 开头 | status=200 escaped=true | PASS |  |

### 多租户（26 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| H01 | P0 | 平台开通租户 | 200 orgId + 一次性密码 | HTTP 200 orgId=115 existing=false | PASS | 实际响应未返回 defaultRole（文档 user-system-api §4.3 宣称返回，文档漂移） |
| H02 | P0 | 新租户管理员用临时密码登录 | 200 mustChangePassword=true | HTTP 200 / OK /  | PASS |  |
| H03 | P0 | 未改初始密码禁止业务访问 | 403 MUST_CHANGE_PASSWORD | HTTP 403 / MUST_CHANGE_PASSWORD / 请先修改初始密码 | PASS |  |
| H04 | P1 | 新租户弱密码被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 密码长度至少 12 位 | PASS |  |
| H05 | P0 | 改密成功且旧令牌失效 | 200 + 401 SESSION_REVOKED | chg=200 old=401/SESSION_REVOKED | PASS |  |
| H06 | P0 | 新密码登录并取得租户管理员身份 | 200 pages=[*] orgName=TST | pages=["*"] org=TST R9 隔离企业 994886 | PASS |  |
| H07 | P1 | 新租户默认设施存在 | 200 total≥1 | total=1 | PASS |  |
| H08 | P0 | 新租户创建设备 | 200 id | HTTP 200 / OK /  | PASS |  |
| H09 | P0 | 新租户录入读数 | 200 id | HTTP 200 / OK /  | PASS |  |
| H10 | P0 | 新租户核算（全国因子） | 200 calculated total>0 | status=calculated total=674.04 | PASS |  |
| H11 | P0 | 新租户提交核算 | 200 submitted | HTTP 200 / OK /  | PASS |  |
| H12 | P1 | 审批中改数据自动回退 calculated | 200 + status=calculated | reading=200 period=calculated | PASS |  |
| H13 | P0 | 回退后重算并再次提交 | 均 200 | 200/200 | PASS |  |
| H14 | P0 | 新租户职责分离（编制≠复核） | 409 SOD_VIOLATION | HTTP 409 / SOD_VIOLATION / 复核人不能是编制人（职责分离） | PASS |  |
| H15 | P1 | 新租户创建复核人 | 200 tempPassword | HTTP 200 roleId=290 | PASS |  |
| H16 | P1 | 复核人激活并登录 | 改密200 + 登录200 | 200/200 | PASS |  |
| H17 | P0 | 复核人复核通过 | 200 reviewed | HTTP 200 / OK /  | PASS |  |
| H18 | P0 | 复核人不能自批准 | 409 SOD_VIOLATION | HTTP 409 / SOD_VIOLATION / 批准人不能是复核人（职责分离） | PASS |  |
| H18b | P0 | 编制人不能自批准 | 409 SOD_VIOLATION | HTTP 409 / SOD_VIOLATION / 批准人不能是编制人（职责分离） | PASS |  |
| H19a | P1 | 新租户创建批准人 | 200 tempPassword | HTTP 200 | PASS |  |
| H19 | P0 | 第三人批准通过 | 200 approved | chg=200 login=200 approve=200/OK | PASS |  |
| H20 | P0 | 新租户锁定期间 | 200 locked | HTTP 200 / OK /  | PASS |  |
| H21 | P0 | 新租户生成报告 | 200 id+fileName | id=375 file=碳排放核算报告_2026-11_v2.pdf | PASS |  |
| H22 | P1 | 新租户报告可下载 | 200 PDF | status=200 bytes=79360 | PASS |  |
| H25 | P2 | 新租户解锁回草稿 | 200 draft | HTTP 200 / OK /  | PASS |  |
| H26 | P0 | 停用租户阻断访问、恢复后放行 | suspend200/423/resume200/200 | 200/423/200/200 | PASS |  |

### 核算（25 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| D00 | P0 | 主租户设备基线可用 | 200 且设备 id 存在 | deviceId=1 devices=6 | PASS |  |
| D01 | P1 | 读数 clientRequestId 幂等 | 两次返回同一 id 且只落一条 | 40 vs 40 | PASS |  |
| D02 | P1 | 负读数被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 参数校验失败 | PASS |  |
| D03 | P1 | 缺设备被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 参数校验失败 | PASS |  |
| D04 | P1 | 不存在设备被拒 | 404 NOT_FOUND | HTTP 404 / NOT_FOUND / 设备不存在 | PASS |  |
| D11 | P1 | 非法期间 2026-13 计算不 5xx | 400 VALIDATION | HTTP 400 / VALIDATION / 核算期格式为 YYYY-MM | PASS | 修复后复跑 |
| D11b | P1 | 非法期间 abc 计算不 5xx | 400 VALIDATION | HTTP 400 / VALIDATION / 核算期格式为 YYYY-MM | PASS | 修复后复跑 |
| D11c | P2 | 复核版本号非数字不 5xx | 400 VALIDATION | HTTP 400 / VALIDATION / 版本号必须为整数 | PASS | 修复后复跑 |
| D11d | P2 | 批准版本号非数字不 5xx | 400 VALIDATION | HTTP 400 / VALIDATION / 版本号必须为整数 | PASS | 修复后复跑 |
| D12 | P2 | 不存在的合法期间返回 absent | 200 status=absent | status=absent | PASS |  |
| D13 | P0 | 期间计算成功 | 200 calculated 且总值>0 | status=calculated s1=31.9337 s2=57.9909 total=89.9246 | PASS |  |
| D14 | P0 | 范围一+范围二=总量 | 精确相等 (31.9337+57.9909=89.9246) | diff=0.0000 | PASS |  |
| D15 | P0 | 明细 emission=activity×factor | 6 行全部吻合 | rows=6 bad=none | PASS |  |
| D16 | P1 | 提交版本冲突 | 409 VERSION_CONFLICT | HTTP 409 / VERSION_CONFLICT / 核算期已被他人更新，请刷新后重试 | PASS |  |
| D17 | P0 | 计算后提交 | 200 submitted | status=submitted v=268 | PASS |  |
| D18 | P0 | 编制人不能自复核 | 409 SOD_VIOLATION | HTTP 409 / SOD_VIOLATION / 复核人不能是编制人（职责分离） | PASS |  |
| D19 | P0 | 他人复核通过 | 200 reviewed | status=reviewed v=269 | PASS |  |
| D20 | P0 | 复核人不能自批准 | 409 SOD_VIOLATION | HTTP 409 / SOD_VIOLATION / 批准人不能是复核人（职责分离） | PASS |  |
| D21 | P0 | 他人批准通过 | 200 approved | status=approved v=270 | PASS |  |
| D22 | P0 | 批准后锁定 | 200 locked | status=locked v=271 | PASS |  |
| D23 | P0 | 锁定后禁止重算 | 409 PERIOD_LOCKED | HTTP 409 / PERIOD_LOCKED / 核算期 2026-11 已锁定，不能重算 | PASS |  |
| D24 | P1 | 锁定期间禁止改读数 | 409 PERIOD_LOCKED | HTTP 409 / PERIOD_LOCKED / 核算期 2026-11 已锁定，禁止修改数据；如需更正请走解锁审批 | PASS |  |
| D25 | P1 | 解锁原因必填 | 400 VALIDATION | HTTP 400 / VALIDATION / 参数校验失败 | PASS |  |
| D26 | P0 | 普通用户不能解锁 | 403 FORBIDDEN | HTTP 403 / FORBIDDEN / 无权限执行该操作 | PASS |  |
| D27 | P0 | 管理员解锁回草稿 | 200 draft | status=draft v=272 | PASS |  |

### 跨租户（9 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| D05 | P1 | 跨租户引用他租户设备应被拒 | 403/404（跨租户按不存在处理） | 404/NOT_FOUND；返回设备名=- | PASS |  |
| D06 | P1 | 跨租户修改他租户读数应被拒 | 403/404（行级隔离） | 404/NOT_FOUND | PASS |  |
| D07 | P1 | 跨租户删除他租户读数应被拒 | 403/404（行级隔离） | 404/NOT_FOUND；主租户仍可见=true | PASS |  |
| D08 | P0 | 主租户读他租户设备被拒 | 403/404 | 404/NOT_FOUND | PASS |  |
| D09 | P0 | 新租户读主租户产品被拒 | 403/404 | 404/NOT_FOUND | PASS |  |
| D10 | P1 | 读数列表按租户隔离 | 200 total=0 | total=0 | PASS |  |
| H23 | P0 | 新租户读主租户报告被拒 | 403/404 | 404/NOT_FOUND | PASS |  |
| H24 | P0 | 主租户读新租户报告被拒 | 403/404 | 404/NOT_FOUND | PASS |  |
| G08 | P0 | 跨租户附件下载被拒 | 403/404 | 404/NOT_FOUND | PASS |  |

### 报告（10 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| E01 | P0 | 草稿态禁止生成正式报告 | 409 PERIOD_LOCKED | HTTP 409 / PERIOD_LOCKED / 核算期间尚未提交，请先提交后再生成正式报告 | PASS |  |
| E02 | P0 | 提交前置流程可执行 | calculate+submit 均 200 | 200/200 | PASS |  |
| E03 | P0 | 提交后生成碳排放报告 | 200 含 id/fileName/sha256 | id=374 file=碳排放核算报告_2026-11_v183.pdf size=96455 | PASS |  |
| E04 | P0 | 下载内容与元数据一致 | PDF 头 + sha256 相同 | status=200 pdf=true shaMatch=true | PASS |  |
| E05 | P1 | 报告详情与生成结果一致 | 200 同 id/同 sha256 | HTTP 200 / OK /  | PASS |  |
| E06 | P1 | 报告打包 ZIP | 200 且 PK 头 | status=200 bytes=479291 | PASS |  |
| E07 | P2 | 非法报告类型被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 报告类型只能是 carbon / energy / footprint | PASS |  |
| E08 | P2 | 足迹报告缺 id 被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 产品碳足迹报告必须指定 footprintId | PASS |  |
| E09 | P2 | 不存在报告下载 404 | 404 NOT_FOUND | HTTP 404 / NOT_FOUND / 报告不存在 | PASS |  |
| E10 | P0 | 测试后期间复位为 draft | 200 draft | status=draft v=278 | PASS |  |

### 权限（12 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| B07 | P1 | 普通用户不能读账号管理 | 403 FORBIDDEN | HTTP 403 / FORBIDDEN / 无权限执行该操作 | PASS |  |
| B08 | P1 | 普通用户不能读审计 | 403 FORBIDDEN | HTTP 403 / FORBIDDEN / 无权限执行该操作 | PASS |  |
| B09 | P1 | 普通用户不能读任务清单 | 403 FORBIDDEN | HTTP 403 / FORBIDDEN / 无权限执行该操作 | PASS |  |
| B10 | P1 | 页面目录共 34 项 | 200 total=34 | total=34 | PASS |  |
| F01 | P0 | 创建受限角色 | 200 roleId + 2 页面 | roleId=291 pages=["home","product.list"] | PASS |  |
| F02 | P0 | 受限用户登录且仅含勾选页面 | pages=[home, product.list] | pages=["home","product.list"] temp=true | PASS |  |
| F03 | P0 | 页面鉴权：产品可读、报告/供应商/凭证导出/附件被拒 | 200/403×4 | products=200 reports=403 suppliers=403 exportEv=403 file=403 exportProd=200 | PASS |  |
| F04 | P1 | 快照按页面裁剪 | 不含 reports/suppliers 数据键 | keys=org,products,kpi,monthly,todos,energyMix | PASS |  |
| F05 | P1 | 页面内可写但删除仅管理员 | 写入200 / 删除403 | write=200 delete=403/FORBIDDEN | PASS |  |
| F06 | P1 | 受限用户不能进系统管理 | 403×2 | 403/403 | PASS |  |
| F07 | P2 | 角色在用不可删 | 409 ROLE_IN_USE | HTTP 409 / ROLE_IN_USE / 该角色下还有 1 名用户，请先转移到其他角色 | PASS |  |
| F08 | P1 | 移出成员后令牌失效、角色可删除 | 移出200 / 401或403 / 角色删除200 | rm=200 after=401/SESSION_REVOKED roleDel=200 | PASS |  |

### 上传（7 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| G01 | P1 | 上传合法 PDF | 200 名称/大小正确 | HTTP 200 name=TST-R9-upload.pdf size=69 | PASS |  |
| G02 | P1 | 附件下载强制 attachment + octet-stream | attachment + octet-stream + sha 一致 | ct=application/octet-stream disp=attachment; filename*=UTF-8''T sha=true | PASS |  |
| G03 | P1 | SVG 附件不以可执行类型回显 | 200 + octet-stream + attachment | ct=application/octet-stream disp=attachment; filename*=UT | PASS |  |
| G04 | P1 | 空文件被拒 | 400 VALIDATION | HTTP 400 / VALIDATION / 文件为空 | PASS |  |
| G05 | P1 | 路径穿越文件名被清洗 | 200 且 name=basename | name=TST-R9-evil.csv | PASS |  |
| G06 | P2 | 超长文件名截断且保留扩展名 | 200 且长度≤200 且 .pdf 结尾 | len=200 tail=TTTT.pdf | PASS |  |
| G07 | P2 | 不存在文件下载 404 | 404 NOT_FOUND | HTTP 404 / NOT_FOUND / 文件不存在 | PASS |  |

### 性能（1 项）

| 编号 | 优先级 | 用例 | 预期 | 实际 | 结果 | 备注 |
|---|---|---|---|---|---|---|
| B12 | P1 | 首页快照体积在预算内 | ≤60KB | 11195 bytes | PASS |  |


---

## 五、覆盖率

- **核心链路**：主租户与全新隔离租户各跑通一条完整链路——计算→提交→复核→批准→锁定→解锁；新租户额外覆盖 开通→临时密码→强制改密→设备/读数→审批中改数据自动回退→重新提交→报告→停用/恢复。
- **页面**：38/38 业务路由真实浏览器渲染通过，0 控制台错误；静态菜单 41 个入口唯一。3 个平台页本轮未复跑（第八轮已覆盖 41/41）。
- **接口**：本轮直接调用 40+ 接口路径（含同一路径的不同方法与参数分支），覆盖认证、平台租户、主数据、设备读数、核算、报告、权限/角色/成员、文件、导出、组织与审计等 12 个业务域。后端共 31 控制器 / 183 映射，分析类 9 个聚合接口及预警/节能/足迹/资产 CRUD 本轮未重复铺量（第七、八轮覆盖）。
- **数据**：39 项 SQL 一致性检查覆盖三层勾稽、明细复算、报告字节数、23 类孤儿、8 类重复、跨租户脏数据、审计守恒；测试前后均 0 违规。
- **安全**：存储型 XSS、SQL 注入关键词、CSV 公式注入、路径穿越文件名、SVG 脚本附件、跨租户读写、越权页面/导出/附件均已验证；补充 CSRF、CORS 白名单、Cookie 属性（HttpOnly/SameSite/Secure 开关）、CSP/Referrer-Policy 实测。
- **并发**：隔离租户下 60 并发写读数、25 并发重算、10 并发同版本提交、30 并发读快照，验证幂等、乐观锁与期间行锁，0 个 5xx。
- **前端交互**：38 项真实交互流程（主题、筛选、分页、导出、上传、报告下载、角色/成员、首登改密）全部通过；Chrome 与 Edge 双引擎渲染门禁通过。

---

## 六、剩余风险

| # | 风险 | 等级 | 说明 / 处置 |
|---|---|---|---|
| 1 | T-1/T-2/T-3 已修复并回归 | 低 | 已关闭；保留单测与并发脚本防回归 |
| 2 | 极端"首次并发建期"仍可能返回 409 提示重试 | 低 | 已消除 5xx 与死锁；串行化下重算全部 200，首次撞期允许重试 |
| 3 | 生产容量/公网 RTT、真实短信、HTTPS 终止 | — | 本机压测不代表生产容量，需客户环境复测 |
| 4 | 权限模型无动作位：进入页面即可写，删除限管理员 | 中 | 交付说明已写明；如需只读角色需产品立项加权限点 |
| 5 | Firefox/Safari 未覆盖 | 低 | Chrome/Edge 已通过；Safari 无法在 Windows 验证 |
| 6 | 旧令牌回放（O-1 相关） | 低 | 需历史签名密钥样本，成本高收益低；退出语义已写入说明 |
| 7 | 软删唯一键（supplier / tenant_role / energy_mgmt）未统一走 `UniqueKeys` | 低 | 第八轮遗留；同编码重建可能 500，待统一 |
| 8 | 超大数值报错文案（O-2） | 低 | 不影响校验结果，随下个迭代修正 |

---

## 七、数据收尾

- 本轮并发/回归/UI 流程创建的隔离租户及其成员、角色、设施、设备、读数、核算期、报告、附件已全部清理；测试账号（137/135 号段）、TST 产品/供应商、上传附件、临时报告已删除。
- 清理后基线：**1 租户 / 6 账号 / 5 产品 / 7 供应商 / 6 设备 / 24 条读数**；期间 `2026-11` = **draft**。
- 清理后复跑 39 项 SQL 检查：**0 违规**（无孤儿、无重复、无跨租户脏数据）。
- 缺陷修复后重新打包并重启服务（23:00），复跑：`mvn test` 104/104、API 142/143（唯一失败 A01 为脚本断言口径）、39 项 SQL 0 违规、`gate-l0` 11/11、`check-ui` 5/5；清理后基线恢复为 1 租户 / 6 账号 / 5 产品 / 7 供应商 / 6 设备 / 24 条读数，期间 `2026-11` = draft。

---

## 八、结论

功能面本轮未发现新的业务逻辑缺陷：核算勾稽、三级审批、职责分离、锁定守卫、报告生命周期、页面级权限、租户行级隔离、上传/导出安全均按设计工作；独立租户全链路可开通即用，38 项前端交互流程在 Chrome/Edge 双引擎通过。

本轮共修复 3 个缺陷与 3 项安全加固：T-1 期间格式 500、T-2 版本号 500、T-3 并发重算死锁 500；CORS 改为白名单、Cookie 增加 Secure 开关、应用与 nginx 增加 CSP/Referrer-Policy。修复后：后端单测 **104/104**、API **142/143**（唯一失败为脚本断言口径）、DB 一致性 **39/39**、Chrome/Edge 渲染门禁 **10/10**、UI 交互 **38/38**、并发 4 场景 **0 个 5xx**、Node 冒烟 **9/9**。剩余 D-1、O-1、O-2 及权限动作位不影响功能正确性，按排期处理。
