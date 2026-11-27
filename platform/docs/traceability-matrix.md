# 四向追溯矩阵（M13）

> 一行 = 一个原子验收条目；四列必须同时有落点：需求 / 页面钩子 / 接口 / 库字段。任一列为空即设计缺口。
> 钩子用路由 + 稳定 DOM id，不用中文文案；表.列来自 `information_schema`，字段名以库为准。
> 更新日期：2026-11-26　覆盖：P0 业务闭环（台账 14 + 核算 + 报告 + 账号 + 预警 + 平台）+ 用户体系 P3（概览 / 审计 / 到期提醒）

## 1. 台账（14 页）

| # | 需求 / AC | 页面与钩子 | 接口 | 库字段 |
|---|---|---|---|---|
| T01 | 产品可新增、编辑、删除、按类型筛选 | `/carbon-product/product/list` `#g-new` `#g-table` `#g-search` | `GET/POST /api/products`、`PUT/DELETE /api/products/{id}` | `product.name/code/model/type/cls/unit/l1/l2/l3/disabled/version` |
| T02 | 产品产值按月登记，同产品同月唯一 | `/carbon-output/production-value/list` `#g-new` `#g-search` | `GET/POST /api/production-values`、`PUT/DELETE /{id}` | `production_value.period/product_id/product_name/output_value/qty/qty_unit/version` |
| T03 | 生产总值按月登记，同月唯一 | `/carbon-output/total-value/list` `#g-new` | `GET/POST /api/total-values`、`PUT/DELETE /{id}` | `total_value.period/name/qty/qty_unit/output_value/version` |
| T04 | 配额资产按年度与类型管理 | `/carbon-asset/asset/list` `#g-new` `#g-search` | `GET/POST /api/carbon-assets`、`PUT/DELETE /{id}` | `carbon_asset.name/type/qty/unit/asset_year/status/disabled/version` |
| T05 | 信用资产（CCER）独立台账 | `/carbon-asset/ccer/list` `#g-new` | 同 T04（`type=信用资产`） | `carbon_asset.*`（同上） |
| T06 | 交易登记扣减库存，售出不足拒绝 | `/carbon-asset/trade/list` `#g-new` | `GET/POST /api/carbon-trades`、`DELETE /{id}` | `carbon_trade.asset_id/asset_name/type/qty/price/amount/party/use_purpose/trade_date` |
| T07 | 凭证归档、有效期、附件下载 | `/carbon-verify/evidence/list` `#g-new` `data-dl` | `GET/POST /api/evidences`、`PUT/DELETE /{id}`、`GET /api/files/{fid}/download` | `evidence.name/code/description/valid_from/valid_to/at_date/version`、`file_object.biz_id/storage_key` |
| T08 | 第三方报告归档与 PDF 预览 | `/carbon-verify/report/list` `#g-new` `data-pdf` | `GET/POST /api/third-party-reports`、`PUT/DELETE /{id}` | `third_party_report.name/description/file_id/valid_from/valid_to/at_date` |
| T09 | 设施两级树、上级引用、禁用 | `/carbon-facility/facility/list` `#g-new` `data-child` | `GET/POST /api/facilities`、`PUT/DELETE /{id}` | `facility.name/code/parent_id/tree_level/sort_no/disabled/version` |
| T10 | 设备读数分页、按设备/状态/日期筛选 | `/carbon-facility/device-data/list` `#g-search` `#g-total` | `GET /api/device-readings/page`、`POST/PUT/DELETE /api/device-readings` | `device_reading.device_id/ts/qty/unit/rate/source/status/remark` |
| T11 | 预警事件字典启停 | `/common-warning/event/list` `#g-new` `data-toggle` | `GET /api/alert-events/page`、`POST/PUT/DELETE /api/alert-events` | `alert_event.name/type/metric/op/threshold/level/builtin/enabled` |
| T12 | 节能改造项目登记 | `/carbon-economize/renovation-project/list` `#g-new` | `GET/POST /api/retrofits`、`PUT/DELETE /{id}` | `retrofit.project_year/name/invest/invest_unit/saving/saving_unit/contract/done` |
| T13 | 能源管理情况按年填报 | `/carbon-economize/management-situation/list` `#g-new` | `GET/POST /api/energy-mgmt`、`PUT/DELETE /{id}` | `energy_mgmt.at_date/org_name/duty/team/post/charge/filler/contact/system_name/filing` |
| T14 | 供应商碳足迹与核算范围 | `/carbon-footprint/supplier-footprint/list` `#g-new` | `GET/POST /api/suppliers`、`GET /api/suppliers/tree`、`PUT/DELETE /{id}` | `supplier.name/product/unit/fp_unit/footprint/date/target/level/parent_id` |

## 2. 核算与报告

| # | 需求 / AC | 页面与钩子 | 接口 | 库字段 |
|---|---|---|---|---|
| A01 | 选月新建即计算，生成排放明细 | `/carbon-computation/calculation-complete/list` `#ac-new` `data-calc` | `POST /api/accounting-periods/{period}/calculate`、`GET /api/accounting-periods/{period}` | `accounting_period.period/status/scope1/scope2/total/calc_version`、`accounting_line.device_id/scope_type/activity/factor_value/emission` |
| A02 | 已计算可提交，提交后禁止改数 | `data-submit` | `POST /api/accounting-periods/{period}/submit` | `accounting_period.status/submitted_by/submitted_at/version`、`audit_log.action=submit` |
| A03 | 复核人与编制人不得同一人 | `data-review` | `POST /api/accounting-periods/{period}/review` | `accounting_period.reviewed_by/reviewed_at/status`、`audit_log.action=review` |
| A04 | 批准人与复核人不得同一人 | `data-approve` | `POST /api/accounting-periods/{period}/approve` | `accounting_period.approved_by/approved_at/status`、`audit_log.action=approve` |
| A05 | 仅已批准可锁定；锁定后禁写 | `data-lock` | `POST /api/accounting-periods/{period}/lock` | `accounting_period.status/locked_by/locked_at`、`audit_log.action=lock` |
| A06 | 解锁必填原因并留痕，回草稿 | `data-unlock` `#ac-reason` | `POST /api/accounting-periods/{period}/unlock` | `period_unlock.period_id/reason/requested_by/approved_by/unlocked_at`、`accounting_period.status` |
| A07 | 报告按类型/期间生成 PDF 并留历史 | `/carbon-report/calculation-report/query` `#r-gen` `#r-query` | `POST /api/reports`、`GET /api/reports?type=` | `report.type/period/footprint_id/file_id/status/name` |
| A08 | 报告下载与 ZIP 打包 | `data-dl` `#r-bundle` | `GET /api/reports/{id}/download`、`GET /api/reports/bundle` | `report.file_id`、`file_object.storage_key` |
| A09 | 首页 KPI 与 12 月趋势 | `/home` `#view .kpi` | `GET /api/bootstrap?year=` | 聚合：`accounting_period.scope1/scope2`、`device_reading.qty/rate`（无独立表） |

## 3. 账号、预警与平台

| # | 需求 / AC | 页面与钩子 | 接口 | 库字段 |
|---|---|---|---|---|
| U01 | 手机号密码登录，失败锁定 | 登录页 `#auth-phone` `#auth-pwd` `#auth-login` | `POST /api/auth/login` | `app_user.phone/password_hash/status/failed_count/locked_until/token_version`、`login_log.result/at` |
| U02 | 短信验证码登录（图形码前置） | `#auth-sms-field` `#auth-cap` | `POST /api/auth/captcha`、`POST /api/auth/sms/send`、`POST /api/auth/sms/login` | `captcha.captcha_id/used_at/expire_at`、`sms_code.phone/code/used_at` |
| U03 | 首次登录强制改密，改密后旧令牌失效 | `#um-pwd` `#pwd-save` | `POST /api/auth/password/change` | `app_user.password_hash/token_version/password_updated_at`、`password_history.password_hash` |
| U04 | 管理员新增成员、改角色、停用、移出 | `/system/users` `#u-new` `#u-table` | `GET/POST /api/users`、`PUT /{id}`、`POST /{id}/disable|enable`、`DELETE /{id}` | `app_user.phone/display_name/status`、`user_tenant.role/status/joined_at` |
| U05 | 最后一个管理员不可移除/停用 | 同上 | 同上 | `user_tenant.role/status` + 计数校验 |
| U06 | 预警记录分页、标记已读、处理、批量删除 | `/common-warning/record/list` `#rec-search` `#rec-bulk` | `GET /api/alert-records`、`POST /{id}/read|handle`、`POST /read-all|batch-delete` | `alert_record.title/level/metric/threshold/metric_value/device_code/status/is_read/handled_by/handled_at` |
| U07 | 企业档案只读+管理员编辑（乐观锁） | `/enterprise/info` `#org-edit` | `GET/PUT /api/org` | `org.name/code/contact/industry/version` |
| U08 | 平台开通租户、停用、套餐 | `/platform/tenants` `#t-new` | `GET/POST /api/platform/tenants`、`POST /{id}/suspend|resume` | `org.name/tenant_code/status/plan/seat_limit/expire_at`、`app_user.phone`、`user_tenant.role` |
| U10 | 超级管理员登录落到平台概览，看 KPI 与 30 日内到期 | `/platform/overview` `#ov-new` `#ov-tenant` `#ov-audit` `[data-ov-renew]` | `GET /api/platform/overview`、`GET /api/platform/stats` | `org.status/plan/seat_limit/expire_at`、`user_tenant.status` |
| U11 | 平台操作跨租户可追溯，按动作/关键词/日期筛选 | `/platform/audit` `#pa-action` `#pa-kw` `#pa-search` `[data-pa-open]` | `GET /api/platform/audit` | `audit_log.org_id/entity/entity_id/action/action_label/before_json/after_json/logged_at` |
| U12 | 租户内写操作可追溯，普通用户不可见 | `/system/audit` `#au-entity` `#au-action` `#au-actor` `#au-search` `[data-au-open]` | `GET /api/audit-logs`、`GET /api/audit-logs/facets` | 同上（`org_id = 当前租户`） |
| U13 | 授权 30 天内站内提醒，7 天内标红 | 全局 `#license-banner` `#lic-view`；`/system/tenant` | `GET /api/auth/me`（`licenseRemainDays/licenseStatus`） | `org.expire_at/status/plan/seat_limit` |

## 4. 空格解释

- A09 首页 KPI 无独立表：由核算与读数实时聚合，口径见 `docs/字段与口径.md`。
- 14 个台账的「企业」列不落字段：单租户上下文取 `org.short_name`，多租户隔离由 `org_id` 保证。
- 报告阶段明细（足迹三阶段）目前由 `product_footprint.stage_*` 汇总，未建阶段明细表，故无独立行。

## 5. 横切能力（2026-11-25 补）

| # | 需求 / AC | 页面与钩子 | 接口 | 库字段 |
|---|---|---|---|---|
| X01 | 创建请求可安全重试：同键同体重放首次结果，同键异体 409 | `assets/api-client.js` `request()` 自动带 5s 复用键 | 全部 `POST /api/**`（认证与 multipart 除外） | `idempotency_record.org_id/idem_key/request_hash/status_code/response_body/expires_at` |
| X02 | 关键动作可追溯：谁在何时改了哪个对象 | `/system/audit`、`/platform/audit` | 由 Service 统一写 `audit_log` | `audit_log.entity/entity_id/action/actor/org_id/logged_at` |
| X03 | 数据一致性可验收：双字段/孤儿/重复/审计守恒 | `tools/data-check.sh` | 直连库只读 SQL | 全库 36 张业务表 |
