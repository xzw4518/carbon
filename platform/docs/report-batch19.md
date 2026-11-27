# 成员三项配置下线（V109）· 2026-11-26

> 决策：成员类型（内部/外部）、数据范围（ORG/SELF）、成员级授权到期三项彻底删除，外部协作能力一并下线。
> 执行：一次性完成（数据库 + 后端 + 前端 + 测试 + 文档），已通过全部门禁。

## 1. 数据库（V109）

`V109__drop_member_flags.sql`：

1. `kind='external'` 的成员 → `status='disabled'`（保留账号与审计，不转为可进业务页的内部员工）
2. 已过期成员（`expires_at < now()`）→ `disabled`，避免删列后“复活”
3. 其余 `expires_at` 清空
4. `data_scope` 统一 `ORG`
5. 删除 `user_tenant.kind / data_scope / expires_at`
6. 删除 `external_grant` 表

执行前备份：`backend/backup/carbonbal-before-v109-20261126-094527.sql`；
授权表归档：`backend/backup/external_grant-20261126-094527.csv`（0 行）。

演示库结果：5 名成员保持 `active`（原本均为 internal/ORG/无到期），V109 `success=1`，三列与授权表均已不存在。

## 2. 代码变更

**后端**
- `UserTenant`、`UserDtos`、`UserService`、`AuthController`、`AuthStateService` 移除三字段及相关校验/分支
- `JwtAuthFilter` 移除外部账号白名单、`EXTERNAL_LIMITED`、`MEMBER_EXPIRED` 判定
- 删除 `common/DataScopeService`，产品/设备读数/原始凭证/第三方报告恢复企业范围查询
- 删除 `external` 包（Controller/Service/Grant/Mapper）与 `AccountingController`、`ReportController` 的授权二次校验
- 错误码移除 `EXTERNAL_LIMITED`、`MEMBER_EXPIRED`

**前端**
- 账号新增/编辑弹窗移除三项；列表移除“外部”标记与“授权”按钮/弹窗
- 删除 `pages-external.js`、外部门户路由、`external` 登录分支、菜单特判
- `api-client` 移除 `kind` 分支、外部授权请求与 `UI.state.external`

## 3. 行为与接口变化（破坏性）

| 项 | 变化 |
|---|---|
| 成员接口 | 创建/编辑/详情不再有 `kind`、`dataScope`、`expiresAt`；旧客户端多传字段会被忽略，不报错 |
| 数据可见性 | 原 `SELF` 成员变为全企业可见（本次演示库无 SELF 成员） |
| 成员有效期 | 只受企业授权期约束；原已过期成员已转停用 |
| 外部协作 | `POST /api/external/**` 返回 404；外部门户页面移除 |

## 4. 测试与文档

- 删除 `ExternalPortalTest`、`MemberExpiryTest`；`UserIdentityTest` 去掉数据范围/外部 2 条
- 功能用例 146 → **142**（移除 R01–R03、R08）
- 交互用例 38 → **37**（移除外部账号流程；成员弹窗增加“三项已消失”断言）
- e2e 108 → **102** 项（移除外部与 SELF 两段）
- 接口 182 → **177**；单测 100 → **95**
- 更新 `permissions.md`、`architecture.md`、`user-system-api.md`、`error-codes.md`、`user-manual.md`、`traceability-matrix.md`、`字段与口径.md`、README/AGENTS/HANDOVER
- `problem-library.md` 与历史报告保留原样（当时问题记录）

## 5. 验收

| 项 | 结果 |
|---|---|
| 单测 | 95/95 |
| 功能用例 | 142/142 |
| 交互流程 | 37/37 |
| 端到端 | 100/102（2 项短信当日配额） |
| 数据 / 文档 / 性能 | 33/33、21/21、14/14 |
| 前端静态 / UI | 11/11、5/5 |

另手工验证：成员新增/编辑只剩用户类型与角色；`/api/external/summary` 404；旧字段请求体不报错；`me` 与成员列表不再返回三字段。

## 6. 回滚

- 代码回滚到本次提交前；
- 数据库用上述备份恢复（V109 删列不可逆，不能只回滚代码）；
- `external_grant` 归档 CSV 可重新导入，但外部门户代码已删除，需要一并回滚。
