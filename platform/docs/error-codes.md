# 错误码表（v1 · 2026-11-25）

> 契约：**只增不改**。新增错误码必须同时补 `ErrorCode.http()` 与本文档；删除或复用视为破坏性变更。
> 信封：成功 `{ "status": 0, "body": {...} }`；失败 `{ "status": "CODE", "body": { "message": "...", "detail": {...} } }`。
> `status` 是字符串业务码；HTTP 状态由下表映射，前端只按 `status` 分支，不解析中文文案。

## 1. 通用

| code | HTTP | 触发条件 | 附加字段 detail | 前端动作 |
|---|---|---|---|---|
| `VALIDATION` | 400 | 参数缺失/格式错/业务校验失败 | 字段 → 提示（表单校验时为对象） | 表单标红，聚焦第一个错误 |
| `UNAUTHORIZED` | 401 | 无会话、未登录 | — | 清状态，跳登录 |
| `FORBIDDEN` | 403 | 角色不足或对象不属于本企业 | — | 隐藏入口；提示无权限 |
| `NOT_FOUND` | 404 | 资源不存在或跨企业访问 | 资源标识 | 提示并刷新列表 |
| `METHOD_NOT_ALLOWED` | 405 | 接口不支持该方法（如 GET 详情接口） | `supported` 允许的方法 | 提示并回列表 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Content-Type 不支持（如上传接口传 JSON） | 实际类型 | 提示用表单上传 |
| `DUPLICATE` | 409 | 唯一键冲突（编码/手机号/年度/期间） | 冲突字段值 | 提示"已存在"，定位字段 |
| `REFERENCED` | 409 | 被引用不能删除（设备有读数、设施有下级） | 子项数量/列表 | 提示先处理引用 |
| `VERSION_CONFLICT` | 409 | 乐观锁版本不一致 | `currentVersion` | 提示刷新后重试 |
| `PERIOD_LOCKED` | 409 | 核算期已锁定 | 期间 | 提示先解锁 |
| `FACTOR_MISSING` | 422 | 缺少可用排放因子 | 因子类型/期间 | 提示补因子 |
| `INSUFFICIENT` | 409 | 库存/配额不足 | 可用数量 | 提示可用量 |
| `SOD_VIOLATION` | 409 | 职责分离（编制≠复核、复核≠批准） | — | 提示换人操作 |
| `CSRF_INVALID` | 403 | CSRF Token 与 Cookie 不一致 | — | 提示刷新页面后重试 |
| `IDEMPOTENCY_CONFLICT` | 409 | 同一幂等键用于不同请求体 | — | 生成新键重试 |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | 同键请求仍在处理中 | — | 稍后重试（不要换键） |
| `TOO_MANY_REQUESTS` | 429 | 通用限流（保留） | 重试秒数 | 倒计时后重试 |
| `INTERNAL` | 500 | 未捕获异常 | 异常摘要 | 提示稍后重试，联系管理员 |

## 2. 认证与会话

| code | HTTP | 触发条件 | 附加字段 | 前端动作 |
|---|---|---|---|---|
| `AUTH_BAD_CREDENTIAL` | 401 | 手机号或密码错误 / 账号不存在（不泄露存在性） | — | 提示"手机号或密码错误" |
| `AUTH_TOKEN_INVALID` | 401 | 临时令牌无效/过期、会话失效 | — | 回登录页 |
| `SESSION_REVOKED` | 401 | `token_version` 变化（改密/停用） | — | 清状态重登 |
| `AUTH_CAPTCHA_INVALID` | 400 | 图形码缺失/错误/过期/已用 | — | 刷新图形码并提示 |
| `AUTH_CODE_INVALID` | 400 | 短信验证码缺失或错误 | — | 提示重输 |
| `AUTH_CODE_EXPIRED` | 400 | 短信验证码过期/已使用 | — | 重新获取 |
| `AUTH_CODE_LIMIT` | 429 | 发送过频、当日超限 | 剩余秒数 | 禁用按钮并倒计时 |
| `AUTH_RATE_LIMITED` | 429 | 登录失败次数过多 | 剩余分钟 | 提示等待 |
| `ACCOUNT_DISABLED` | 423 | 账号停用 | — | 提示联系管理员 |
| `ACCOUNT_LOCKED` | 423 | 连续失败锁定 | 解锁时间 | 提示 15 分钟后再试 |
| `PASSWORD_TEMP_EXPIRED` | 423 | 临时密码过期 | — | 提示联系管理员重置 |
| `PASSWORD_NOT_SET` | 400 | 账号无密码，只能短信登录 | — | 切换短信登录 |

## 3. 企业与成员

| code | HTTP | 触发条件 | 附加字段 | 前端动作 |
|---|---|---|---|---|
| `TENANT_NOT_FOUND` | 404 | 无该企业成员关系 | — | 返回选择企业 |
| `TENANT_SUSPENDED` | 423 | 企业停用/到期 | — | 提示联系管理员 |
| `TENANT_EXPIRED` | 403 | 企业授权到期（读接口同样拒绝，无宽限期） | 到期日 | 提示联系平台续期 |
| `TENANT_PENDING` | 403 | 企业授权尚未生效（开始日期未到） | 开始日 | 提示生效时间 |
| `TENANT_LICENSE_INVALID` | 422 | 授权期缺失/到期早于开始/早于今天 | 字段 | 修正日期 |
| `SEAT_LIMIT` | 409 | 席位数已用尽或小于当前成员数 | used/limit | 平台扩容或先移出成员 |
| `PAGE_FORBIDDEN` | 403 | 角色未被授予该页面 | pageCode | 提示联系管理员或隐藏入口 |
| `ROLE_LIMIT` | 409 | 角色数超过 30，或删除最后一个角色 | limit | 先删无用角色 |
| `ROLE_IN_USE` | 409 | 角色下仍有用户 | users | 先转移用户 |
| `ROLE_DEFAULT` | 409 | 默认角色【运营】不可删除 | — | 改为编辑页面 |
| `MEMBER_PENDING` | 403 | 成员未确认邀请 | — | 提示先确认 |
| `MEMBER_DISABLED` | 403 | 成员被停用/移出 | — | 重登或联系管理员 |
| `MEMBER_LAST_ADMIN` | 409 | 移除/停用最后一个管理员 | — | 提示至少保留一名 |
| `PHONE_TAKEN` | 409 | 换绑手机号已被占用 | — | 提示换号 |
| `SELF_OPERATION` | 409 | 对自己降级/停用/移出 | — | 禁止并提示 |

## 4. 使用约定

- 后端：统一抛 `BizException`（`validation/forbidden/duplicate/...`），由 `GlobalExceptionHandler` 转信封；过滤器用 `{"status":"CODE","body":{"message":...}}`。
- 前端：`request()` 抛出的 `err.code` 即字符串码，`err.http` 为 HTTP 状态；提示文案用 `message`，分支用 `code`。
- 测试：断言 `body.status` 字符串码与 HTTP 状态两者；新增码必须补一条反例。
- 幂等：POST 创建类接口支持 `Idempotency-Key` 请求头（≤128 字符，24h 有效）；重放响应带 `Idempotent-Replay: true`。
