# 平台后端交付报告 · 第六批（用户体系多租户）

- 日期：2026-11-25
- 范围：用户体系多租户落地（账号、成员、角色、登录双通道、租户隔离、核算四岗）
- 依据：`docs/user-tenancy-plan.md`（19 条决策，附录 A–H）

## 1. 交付内容

| 模块 | 内容 | 迁移 |
|---|---|---|
| 租户 | `org` 扩 9 列：status / plan / seat_limit / expire_at / suspended_at / parent_org_id / org_level / tenant_code / timezone | V100 |
| 账号 | `app_user` 扩 12 列：phone / email / status / token_version / must_change_password / password_updated_at / temp_password_expires_at / last_org_id / failed_count / locked_until / deleted_at / password_algo；`password_hash`、`username`、`role`、`org_id` 放宽为可空 | V100/V101/V102 |
| 成员 | 新表 `user_tenant`（org_id + user_id 唯一，role/status/kind/data_scope/employee_no/title/expires_at/removed_at/version） | V100 |
| 登录 | 新表 `sms_code`、`captcha`、`login_log` | V100 |
| 密码 | 新表 `password_history`（唯一键防重复） | V100 |
| 手机号 | 新表 `phone_history`（换绑留痕） | V100 |
| 隔离 | `audit_log` / `accounting_line` / `period_unlock` 补 `org_id` 并回填；`TenantLineInnerInterceptor` 恢复 | V100 |
| 核算 | `accounting_period` 补 submitted_by/at、reviewed_by/at、approved_by/at | V100 |

## 2. 关键实现

- **一人一账号、可多租户**：角色挂成员关系；账号级与成员级停用分离，管理员停用只影响本企业。
- **登录双通道**：手机号+密码 / 手机号+短信；图形码前置、验证码频控（60s/天 10 条，IP 每小时 20 条）、
  原子消费防并发；账号锁定落库（5 次 / 15 分钟），成功登录清零。
- **多企业**：登录返回 `tempToken + tenants`，选完换正式令牌；`switch-tenant` 支持登录后切换。
- **令牌**：JWT 带 `uid/phone/org/role/tv`；HttpOnly Cookie 优先、Bearer 兼容；CSRF double-submit；
  账号状态/令牌版本/成员关系缓存 30 秒，改密或停用即失效。
- **密码**：≥12 位四类字符、90 天过期、最近 3 次不可复用、首登强制改密、临时密码 7 天有效。
- **职责分离**：核算 `submitted → reviewed → approved → locked`；编制人不能复核、复核人不能批准，违规 409。
- **成员管理**：新增/改角色/重置/启停/移出/解锁/导入导出/登录日志；不能停用或降级自己、不能移除最后一名管理员、
  跨企业访问一律 404；离职交接按租户批量转移 `created_by`。
- **前端**：登录页双页签（密码/验证码）+ 图形码 + 企业选择；「系统管理 → 账号管理」；
  顶栏「切换企业」（清空快照重拉）；改密弹窗接真实接口并按后端策略校验。

## 3. 验收

```
mvn test      52/52       （H2，含职责分离与申请复核拦截）
e2e.sh        85/85       （真 MySQL 8.0.28；证据 target/e2e-v11.out）
smoke.sh      9/9
Flyway        v1 → v102（V100 用户体系、V101 username 可空、V102 role/org_id 可空）
表 / 接口      33 张业务表（物理 34）/ 141 个 API
前端实测       登录页双页签、管理员登录、账号页 5 人、界面新增成员返回临时密码、
              未改密访问业务接口 403 → 改密 200 → 新密码登录 200 → 旧令牌 401
```

## 4. 已知约束

| 项 | 说明 |
|---|---|
| 短信通道 | 当前 `LogSmsSender`（固定码 123456，只打日志）；接实名短信需替换实现并配置模板 |
| 用户名兼容 | `/api/auth/login` 接受 `username` 作为回退，一版后移除 |
| 缓存 | 账号/成员状态为单实例内存缓存（30s）；多实例需换 Redis |
| 数据范围 | `data_scope` 仅落库，服务层暂按 `ORG` 处理，SELF/DEPT 未启用 |
| 图形码 | 自绘 SVG（4 位），无障碍与强度可再提升；高安全场景建议换滑块或第三方 |
| 外部协作者 | `kind=external` 与 `expires_at` 已支持到期失效，`external_grant` 细粒度授权未做 |
| 集团层级 | `parent_org_id/org_level` 已建列，集团汇总页未做 |
| 平台端 | 租户开通仍走 SQL SOP（`docs/user-tenancy-plan.md` 附录 C），平台运营端未做 |

## 5. 运维与回滚

- 迁移全部为增量（加列/加表），不改既有列类型；回滚脚本见 `docs/rollback/`（如需）。
- 回滚到 v99 之前：`DELETE FROM flyway_schema_history WHERE version >= '100'` 并删除新增表/列（不建议，会丢账号数据）。
- 账号锁定可用 `POST /api/users/{id}/unlock` 由管理员解除；忘记密码由管理员重置。
