# 第十六批交付报告：全平台测试与缺陷修复

> 日期：2026-11-26　范围：全平台一轮系统测试（接口 / 页面 / 权限 / 健壮性）
> 依据：`traceability-matrix.md`、`permissions.md`、`error-codes.md`、REQ-20261126-001

## 1. 测试范围与方法

| 层 | 手段 | 覆盖量 |
|---|---|---|
| 单元 / 集成 | `mvn test`（H2，MockMvc） | 88 条，含新增 8 条 |
| 端到端 | `tools/e2e.sh`（真实 HTTP + MySQL） | 108 项 |
| 接口探测 | 临时脚本：三种身份 × 66 个 GET + 25 个边界入参 + 10 个越权写 + 未登录 5 项 | 106 次请求 |
| 页面探测 | Playwright：43 条路由（管理员）＋ 6 个新增弹窗＋增删改闭环＋开通向导＋仅首页角色＋平台账号＋外部协作者 | 3 种身份 |
| 门禁 | `gate-l0`、`check-ui`、`data-check`、`check-docs`、`perf` | 11 / 5 / 33 / 21 / 14 |

数据库侧核对：38 张业务表、12 张核心表明细、演示数据未被测试污染（产品 10 条、账号 6 个、租户 1 家、角色 1 个）。

## 2. 发现与修复（14 项）

| # | 级别 | 现象 | 根因 | 修复 |
|---|---|---|---|---|
| 1 | 高 | 只有「产品列表」权限的用户可导出凭证、交易、预警等任意资源 | `GET /api/export` 不按 `resource` 校验页面，统一前缀未命中页面规则 | `PageCatalog.pageForExportResource` + 控制器校验，未授权 403 `PAGE_FORBIDDEN` |
| 2 | 高 | 任意登录用户可按 id 下载本租户任意附件、列附件 | `/api/files` 三个接口无页面校验 | 需 `evidence.list` 或 `third.report`；新增 `PageAccess.allowsAny` |
| 3 | 高 | 无权限用户可下载导入模板并导入（产品/资产/供应商/设施） | `/api/import` 与 `/api/import/template` 只校验资源存在 | 按 `resource` 映射页面，逐项校验 |
| 4 | 中 | 普通用户可读排放因子 | `/api/emission-factors` 未登记页面规则 | 归入 `accounting.list` |
| 5 | 中 | 普通用户可读定时任务清单 | `GET /api/jobs` 无权限注解 | 收紧为 `hasRole('ADMIN')` |
| 6 | 中 | 普通用户直接输 `#/system/users` 能看到账号管理空壳；平台账号能渲染业务页 | 系统管理/平台管理菜单项没有 `page` 编码，路由守卫只按 `page` 判断 | 新增 `guardOfPath`：按 `adminOnly` / `platform` 标记拦截，统一显示无权限页 |
| 7 | 中 | `?page=abc`、`?deviceId=abc`、`?from=abc` 返回 500 | 未处理 `MethodArgumentTypeMismatchException` | 统一 400 `VALIDATION` |
| 8 | 中 | `/api/export` 不带 resource、`/api/import/template` 不带 resource 返回 500 | 未处理 `MissingServletRequestParameterException` | 统一 400，提示缺哪个参数 |
| 9 | 低 | `GET /api/products/1` 返回 500 | 未处理 `HttpRequestMethodNotSupportedException` | 405 `METHOD_NOT_ALLOWED`（新增错误码） |
| 10 | 低 | 上传接口传 JSON / 不带文件字段返回 500 | 未处理 `MultipartException`、`MissingServletRequestPartException` | 400；非表单类型 415（新增 `UNSUPPORTED_MEDIA_TYPE`） |
| 11 | 高 | 外部协作者登录后被弹回登录页，门户进不去 | 登录响应缺 `kind`，前端识别不到外部账号 → 继续拉 `/api/bootstrap` 403 | 登录/切换/代管统一返回 `kind`、`dataScope`；外部账号跳过快照 |
| 12 | 中 | 外部门户「下载 PDF」点了没反应 | `pages-external.js` 未引入 `busy`，点击即 ReferenceError | 补齐解构；顺带把外部门户菜单收成一项「我的授权」 |
| 13 | 中 | 新建租户的租户管理员在用户列表/`me` 里显示为普通用户 | `createTenant` 插入成员时未写 `user_type` | 写入 `tenant_admin`，`me` 返回角色名「租户管理员」 |
| 14 | 中 | 东八区凌晨 0–8 点，新增表单的日期默认值差一天（如开通向导开始日显示昨天） | 用 `toISOString()`（UTC）取“今天” | 统一改为本地日期函数（5 个文件 8 处） |

## 3. 新增回归用例

| 测试类 | 条数 | 覆盖 |
|---|---|---|
| `PageGateTest` | 5 | 导出/导入/附件/因子/任务清单的页面门禁；坏入参 400/405/415 |
| `TenantIsolationTest` | 1 | 跨租户列表不可见、跨租户修改 404、导出不含他租户数据、审计隔离、默认角色 |
| `SessionTenantTest` | 2 | 双租户切换（数据随之切换）、角色改动后旧令牌 401 `SESSION_REVOKED` |

`mvn test` 80 → **88**；e2e 新增 6 项 P3 断言（上一批），本轮无新增脚本项。

## 4. 最终门禁

| 项 | 结果 |
|---|---|
| 单元测试 | **88/88** |
| 端到端 | **106/108**（2 项短信日配额，短信通道在范围外） |
| 静态门禁 | 11/11（41 个入口） |
| UI 门禁 | 5/5（38 路由、0 控制台错误、6 页移动端无溢出） |
| 数据一致性 | 33/33 |
| 文档一致性 | 21 份活文档 0 处过期口径 |
| 性能预算 | 14/14（首屏 FCP 336ms、bootstrap 9KB） |

## 5. 遗留与说明

- 短信发送/登录两项 e2e 失败为**当日配额耗尽**（`AUTH_CODE_LIMIT`），客户环境接入真实短信通道后不受影响；本地可改系统日期或清 `sms_code` 表复跑。
- `GET /api/products/{id}` 本来就没有实现（前端不需要），现在返回 405 而非 404；如需详情接口按 D2 走需求卡。
- 审计日志不提供导出与删除（只追加），平台审计中指向已删除测试租户的记录由 `purge-test-data.sh` 清理。
- 附件页签（`evidence.list` / `third.report`）是文件接口的唯一入口；若将来新增附件场景，需要同步扩 `FILE_PAGES`。
