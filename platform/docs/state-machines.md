# 状态机（M03 · 五列表）

> 每行五列：**状态 / 事件 / 触发者 / 副作用（同事务）/ 审计动作**。终态唯一；未实现的状态不写入本表。
> 代码位置：`accounting/AccountingService`、`identity/UserService`、`auth/AuthController`、`platform/PlatformService`。

## 1. 核算期 `accounting_period.status`

| 状态 | 事件 | 触发者 | 副作用（同事务） | 审计动作 |
|---|---|---|---|---|
| `draft` | 计算/重算 | admin、filler | 写 `accounting_line`；回填 `scope1/scope2/total/calc_version`；`version+1` | `calculate` |
| `draft` | 提交 | admin、filler | `status=submitted`；`submitted_by/at`；`version+1` | `submit` |
| `calculated` | 重算 | admin、filler | 覆盖明细与合计；`calc_version+1`；`version+1` | `calculate` |
| `calculated` | 提交 | admin、filler | 同上 | `submit` |
| `submitted` | 复核 | admin、reviewer | `status=reviewed`；`reviewed_by/at`；`version+1`；校验复核人≠编制人 | `review` |
| `reviewed` | 批准 | admin、approver | `status=approved`；`approved_by/at`；`version+1`；校验批准人≠复核人 | `approve` |
| `approved` | 锁定 | admin | `status=locked`；`locked_by/at`；`version+1`；此后所有写入被 `PeriodGuard` 拒绝 | `lock` |
| `locked` | 解锁 | admin | 写 `period_unlock`（原因、申请人、批准人、时间）；`status=draft`；清空 `locked_*`；`version+1` | `unlock` |

终态：`locked`（可由解锁回到 `draft`，属受控回退，不是自由编辑）。`submitted` 不可直接锁定（必须经 `reviewed → approved`）。

## 2. 账号 `app_user.status`

| 状态 | 事件 | 触发者 | 副作用（同事务） | 审计动作 |
|---|---|---|---|---|
| `pending` | 首次设密/管理员激活 | 本人、admin | `status=active`；`must_change_password=0`；`password_updated_at` | `create` / `update` |
| `active` | 连续登录失败 5 次 | 系统 | `locked_until=now+15min`；`failed_count+1`；`login_log.result=bad_password` | 无（登录日志） |
| `active` | 管理员停用 | admin | `status=disabled`；`token_version+1`（旧令牌立即失效） | `disable` |
| `disabled` | 管理员启用 | admin | `status=active` | `enable` |
| `locked` | 15 分钟到期或管理员解锁 | 系统、admin | 清 `locked_until`；`failed_count=0` | `enable`（人工解锁时） |
| `active` / `disabled` | 重置密码 | admin | 生成临时密码；`must_change_password=1`；`token_version+1` | `reset-password` |
| `active` | 改密 | 本人 | 写 `password_history`；`password_hash` 更新；`token_version+1`；`password_updated_at` | `password_change` |

约束：手机号全局唯一；管理员不能停用/降级自己；至少保留一名管理员。

## 3. 成员关系 `user_tenant.status`

| 状态 | 事件 | 触发者 | 副作用（同事务） | 审计动作 |
|---|---|---|---|---|
| `pending` | 手机号持有人确认 | 本人 | `status=active`；`joined_at` | `activate` |
| `active` | 管理员停用 | admin | `status=disabled`；该成员业务请求被 403 `MEMBER_DISABLED` | `disable` |
| `disabled` | 管理员启用 | admin | `status=active` | `enable` |
| `active` | 移出企业 | admin | `status=removed`；交接创建人数据（逐表容错） | `remove` / `handover` |
| `pending` | 邀请过期/撤销 | 系统、admin | `status=removed` 或 `expired` | `remove` |

约束：同一 `(user_id, org_id)` 唯一；最后一个管理员不可移出/停用。

## 4. 租户 `org.status`

| 状态 | 事件 | 触发者 | 副作用（同事务） | 审计动作 |
|---|---|---|---|---|
| `active` | 平台停用 | platform_admin | `status=suspended`；该租户成员请求 423 `TENANT_SUSPENDED` | `suspend` |
| `suspended` | 平台恢复 | platform_admin | `status=active` | `resume` |
| `active` | 到期 | 系统（定时） | `status=expired` 或 `expire_at` 到期；写操作 403 `TENANT_EXPIRED` | `expire` |
| `expired` | 续期 | platform_admin | 延长 `expire_at`；`status=active` | `renew` |
| `active` | 平台关闭 | platform_admin | `status=disabled`；成员不可登录 | `disable` |

## 5. 不可达/未实现状态（显式登记）

- `app_user.deleted`：设计稿有、代码未实现；账号只停用不物理删。如后续需要注销，按"先匿名化、再保留审计"新增，不复用 `disabled`。
- 邀请令牌 `user_token`：当前用"管理员建成员 + 一次性临时密码"实现，无独立邀请令牌表；附录 B 的 `issued/pending/used` 不适用，待引入邀请链接时再落地。
