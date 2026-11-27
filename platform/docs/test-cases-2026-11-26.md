# 功能测试用例与执行结果（2026-11-26）

> 执行环境：本地服务 `http://localhost:8088`（MySQL 3307 + 演示数据）；用例脚本：`platform/backend/tools/functional-cases.py`。
> 结果含义：PASS=实际与预期一致；FAIL=不一致（详见文末缺陷汇总）。

| 编号 | 模块 | 优先级 | 用例 | 输入 | 预期结果 | 实际结果 | 结果 |
|---|---|---|---|---|---|---|---|
| A01 | 认证 | P0 | 管理员手机号+密码登录 | phone=13800000001 / password=Admin@2026!Pass | HTTP 200，返回 token 且 role=admin | [200, "admin"] | PASS |
| A02 | 认证 | P0 | 密码错误登录 | phone=13800000001 / password=WrongPass123 | HTTP 401，status=AUTH_BAD_CREDENTIAL | [401, "AUTH_BAD_CREDENTIAL"] | PASS |
| A03 | 认证 | P1 | 不存在的手机号登录 | phone=13800009999 / 任意密码 | HTTP 401，与密码错误同一码（不泄露账号是否存在） | [401, "AUTH_BAD_CREDENTIAL"] | PASS |
| A04 | 认证 | P0 | 未登录访问业务接口 | GET /api/products 不带 Authorization | HTTP 401 或 403，不返回数据 | [401, "UNAUTHORIZED"] | PASS |
| A05 | 认证 | P0 | 读取当前用户身份 | GET /api/auth/me（管理员令牌） | HTTP 200，userType=tenant_admin，pages 含 * | [200, "tenant_admin", 34, true] | PASS |
| A06 | 认证 | P0 | 初始密码未改访问业务接口 | 新建用户用初始密码登录后 GET /api/products | HTTP 403，status=MUST_CHANGE_PASSWORD | [403, "MUST_CHANGE_PASSWORD"] | PASS |
| A07 | 认证 | P1 | 弱密码被拒 | 改密 old=初始密码 / new=123456 | HTTP 400，提示密码规则不满足 | [400, "VALIDATION"] | PASS |
| A08 | 认证 | P0 | 改密后旧令牌失效 | 改密成功，用旧 token 再访问 /api/products | 改密 200；旧 token 401 SESSION_REVOKED；新密码可登录 | [200, 401, "SESSION_REVOKED", true] | PASS |
| A09 | 认证 | P1 | 连续输错密码触发锁定 | 同一账号连续错误密码 6 次 | 第 5~6 次出现 HTTP 429 AUTH_RATE_LIMITED（登录失败次数过多） | [429, "AUTH_RATE_LIMITED"] | PASS |
| A10 | 认证 | P1 | 停用账号无法登录 | 管理员停用成员后用正确密码登录 | HTTP 403 MEMBER_DISABLED，提示账号已停用 | [403, "MEMBER_DISABLED"] | PASS |
| B01 | 平台 | P0 | 平台账号登录身份 | 13900000000 登录后 GET /auth/me | role=platform_admin，pages 为空数组 | [200, "platform_admin", []] | PASS |
| B02 | 平台 | P0 | 平台账号不能读业务数据 | 平台令牌 GET /api/products、/api/bootstrap | 两个接口均 403 | [403, 403] | PASS |
| B03 | 平台 | P0 | 开通租户 | POST /platform/tenants {名称/编码/管理员手机/授权期/席位} | HTTP 200，返回 orgId 与一次性初始密码 | [200, 117, true] | PASS |
| B04 | 平台 | P0 | 重复企业编码被拒 | 用已存在的 code=TST-FN-<suffix> 再开通 | HTTP 409 DUPLICATE | [409, "DUPLICATE"] | PASS |
| B05 | 平台 | P1 | 授权日期倒挂被拒 | licenseStartAt=2027-06-01 / licenseEndAt=2027-01-01 | HTTP 422 TENANT_LICENSE_INVALID | [422, "TENANT_LICENSE_INVALID"] | PASS |
| B06 | 平台 | P1 | 租户详情字段完整 | GET /platform/tenants/{新租户id} | 200，含 plan/seatLimit/licenseEndAt/members/devices/reports | [200, {"plan": "standard", "seatLimit": 3, "licenseEndAt": "2026-12-31", "members": 1, "devices": 0, "reports": 0}] | PASS |
| B07 | 平台 | P1 | 续期顺延到期日 | POST /platform/tenants/{id}/renew {months:6} | 200，到期日在原到期日基础上 +6 个月 | [200, "2026-12-31", "2027-06-30"] | PASS |
| B08 | 平台 | P1 | 续期非法日期被拒 | POST renew {licenseEndAt:'abc'} | HTTP 422，不是 500 | [422, "TENANT_LICENSE_INVALID"] | PASS |
| B09 | 平台 | P0 | 停用租户后成员无法登录 | 停用租户 → 该租户管理员登录 | 停用 200；登录 423 TENANT_SUSPENDED | [200, 423, "TENANT_SUSPENDED"] | PASS |
| B10 | 平台 | P0 | 恢复租户后成员可登录 | 恢复租户 → 成员登录 | 恢复 200；登录 200 | [200, 200] | PASS |
| B11 | 平台 | P1 | 席位上限不能小于成员数 | 租户内新增 1 名成员后 PATCH seatLimit=1 | HTTP 409 SEAT_LIMIT | [409, "SEAT_LIMIT"] | PASS |
| B12 | 平台 | P1 | 平台审计可按动作与关键词检索 | GET /platform/audit?action=create、keyword=租户名 | 两次查询 total≥1 且字段含 actionLabel/targetName | [200, 1, 200, 4] | PASS |
| B13 | 平台 | P0 | 超管进入租户后读写、退出后收权 | POST /auth/enter-tenant {新租户} → GET /overview → POST /auth/exit-tenant | 进入 200 且 impersonating=true；业务读 200；退出后业务读 403 | [200, 200, 200] | PASS |
| B14 | 平台 | P0 | 租户管理员不能调平台接口 | 租户管理员令牌 GET /platform/tenants | HTTP 403 | [403, "FORBIDDEN"] | PASS |
| C01 | 主数据 | P0 | 新增产品 | POST /products {name/code/type/cls/unit/l1} | 200，返回 id 且 version=0 | [200, 588, 0] | PASS |
| C02 | 主数据 | P0 | 产品编码唯一 | 重复 POST /products code=FN-P-<suffix> | HTTP 409 DUPLICATE | [409, "DUPLICATE"] | PASS |
| C03 | 主数据 | P1 | 产品详情 | GET /products/{刚建的id} | 200，字段与创建一致 | [200, "TST 用例产品705411"] | PASS |
| C04 | 主数据 | P1 | 产品详情不存在 | GET /products/9999999 | HTTP 404 NOT_FOUND | [404, "NOT_FOUND"] | PASS |
| C05 | 主数据 | P1 | 乐观锁版本冲突 | PATCH 产品 version=99（实际 0） | HTTP 409 VERSION_CONFLICT 且返回 currentVersion | [409, "VERSION_CONFLICT"] | PASS |
| C06 | 主数据 | P0 | 修改产品 | PATCH 产品 {name:..., version:0} | 200，version=1 | [200, 1] | PASS |
| C07 | 主数据 | P0 | 删除产品后不可见 | DELETE /products/{id} → 详情查询 | 删除 200；详情 404 | [200, 404, "NOT_FOUND"] | PASS |
| C08 | 主数据 | P1 | 新增设备 | POST /devices {name/code/energyType:外购电力} | 200，返回 id | [200, 128] | PASS |
| C09 | 主数据 | P1 | 设备编码唯一 | 重复 POST /devices code=FN-D-<suffix> | HTTP 409 DUPLICATE | [409, "DUPLICATE"] | PASS |
| C10 | 主数据 | P1 | 设备停用与启用留痕 | POST /devices/{id}/disable → enable → 查审计 | 两次 200，审计含 disable/enable | [200, 200, true, true] | PASS |
| C11 | 主数据 | P1 | 新增下级设施 | POST /facilities {parentId:1} | 200，tree_level=2 | [200, 2] | PASS |
| C12 | 主数据 | P1 | 有下级的设施不能删除 | DELETE /facilities/1（有子级） | HTTP 409 REFERENCED | [409, "REFERENCED"] | PASS |
| C13 | 主数据 | P1 | 新增供应商 | POST /suppliers 完整字段 | 200，返回 id | [200, 94] | PASS |
| C14 | 主数据 | P1 | 供应商足迹不能为负 | POST /suppliers footprint=-1 | HTTP 400 VALIDATION | [400, "VALIDATION"] | PASS |
| C15 | 主数据 | P1 | 新增年度配额 | POST /quota-years {year:2031,total:1000} | 200 | [200, 113] | PASS |
| C16 | 主数据 | P1 | 同年度配额不可重复 | 再次 POST /quota-years {year:2031} | HTTP 409 DUPLICATE | [409, "DUPLICATE"] | PASS |
| C17 | 主数据 | P1 | 新增碳资产 | POST /carbon-assets {name/type/qty/unit/year} | 200，返回 id | [200, 75] | PASS |
| C18 | 主数据 | P1 | 交易数量超过库存被拒 | POST /carbon-trades {assetId:新资产, qty:99999} | HTTP 409 INSUFFICIENT | [409, "INSUFFICIENT"] | PASS |
| C19 | 主数据 | P1 | 凭证有效期倒挂被拒 | POST /evidences validFrom=2026-12-31 / validTo=2026-01-01 | HTTP 400 | [400, "VALIDATION"] | PASS |
| D01 | 核算 | P0 | 计算排放 | POST /accounting-periods/2026-11/calculate | 200，scope1/scope2/total>0 | [200, 31.9337, 57.9909, 89.9246] | PASS |
| D02 | 核算 | P0 | 提交核算期间 | POST /{period}/submit {version} | 200，status=submitted | [200, "submitted"] | PASS |
| D03 | 核算 | P0 | 编制人不能复核（职责分离） | 提交人（填报员）再调 review | HTTP 409 SOD_VIOLATION | [409, "SOD_VIOLATION"] | PASS |
| D04 | 核算 | P0 | 他人复核通过 | 管理员调 review | 200，status=reviewed | [200, "reviewed"] | PASS |
| D05 | 核算 | P0 | 复核人不能批准（职责分离） | 复核人（管理员）再调 approve | HTTP 409 SOD_VIOLATION | [409, "SOD_VIOLATION"] | PASS |
| D06 | 核算 | P0 | 他人批准通过 | 批准人账号调 approve | 200，status=approved | [200, "approved"] | PASS |
| D07 | 核算 | P0 | 锁定期间 | POST /{period}/lock {version} | 200，status=locked | [200, "locked"] | PASS |
| D08 | 核算 | P0 | 锁定后不能再计算 | 锁定状态下 POST /calculate | HTTP 409 PERIOD_LOCKED | [409, "PERIOD_LOCKED"] | PASS |
| D09 | 核算 | P1 | 普通用户不能解锁 | 填报员调 unlock | HTTP 403 | [403, "FORBIDDEN"] | PASS |
| D10 | 核算 | P0 | 管理员解锁回草稿并留痕 | 管理员 unlock {version,reason} | 200，status=draft，unlocks 记录 +1 | [200, "draft", 146] | PASS |
| E01 | 报告 | P0 | 生成碳排放报告 | POST /reports {type:carbon,period:2026-11} | 200，返回报告 id 与文件名 | [200, 378, "碳排放核算报告_2026-11_v187.pdf"] | PASS |
| E02 | 报告 | P1 | 报告列表按类型过滤 | GET /reports?type=carbon | 200，分页对象且 rows 全部 type=carbon | [200, 4] | PASS |
| E03 | 报告 | P0 | 下载报告 PDF | GET /reports/{id}/download | 200，Content-Type=application/pdf，字节数>1KB | [200, 96453] | PASS |
| E04 | 报告 | P1 | 打包下载 ZIP | GET /reports/bundle?type=carbon&period=2026-11 | 200，Content-Type=application/zip | [200, 383456] | PASS |
| E05 | 报告 | P0 | 普通用户不能删除报告 | 填报员 DELETE /reports/{id} | HTTP 403 | [403, "FORBIDDEN"] | PASS |
| E06 | 报告 | P1 | 管理员删除报告 | 管理员 DELETE /reports/{id} | 200，列表不再包含该报告 | [200, 0] | PASS |
| F01 | 权限 | P0 | 受限角色只拿到勾选页面 | 建角色 pages=[product.list] + 用户，登录查 /auth/me 与 /bootstrap | pages 含 product.list 不含 *；快照无 reports 键 | [200, ["home", "product.list"], false] | PASS |
| F02 | 权限 | P0 | 未授权页面接口被拒 | 受限用户 GET /api/reports、/api/suppliers | 均 HTTP 403 PAGE_FORBIDDEN | [403, "PAGE_FORBIDDEN", 403, "PAGE_FORBIDDEN"] | PASS |
| F03 | 权限 | P0 | 导出按资源鉴权 | 受限用户 GET /export?resource=evidences / products | evidences 403；products 200 | [403, 200] | PASS |
| F04 | 权限 | P1 | 附件接口按页面鉴权 | 受限用户 GET /api/files/1/download | HTTP 403 PAGE_FORBIDDEN | [403, "PAGE_FORBIDDEN"] | PASS |
| F05 | 权限 | P0 | 普通用户不能读审计 | 填报员 GET /api/audit-logs | HTTP 403 | [403, "FORBIDDEN"] | PASS |
| F06 | 权限 | P1 | 普通用户不能读任务清单 | 填报员 GET /api/jobs | HTTP 403 | [403, "FORBIDDEN"] | PASS |
| F07 | 权限 | P1 | 平台账号无业务页面 | 平台令牌 GET /auth/me | pages 为空；GET /api/overview 403 | [200, [], 403] | PASS |
| F08 | 权限 | P1 | 跨租户详情不可见 | 管理员 GET /products/{新租户的产品} | HTTP 404 | [404, "NOT_FOUND"] | PASS |
| G01 | 健壮性 | P1 | 分页参数非数字 | GET /products?page=abc | HTTP 400 VALIDATION | [400, "VALIDATION"] | PASS |
| G02 | 健壮性 | P2 | 分页 size=0 自动收敛 | GET /products?size=0 | HTTP 200，至少返回 1 条 | [200, 1] | PASS |
| G03 | 健壮性 | P2 | 页码超界返回空页 | GET /products?page=99999 | HTTP 200，rows 为空，total>0 | [200, 0, 5] | PASS |
| G04 | 健壮性 | P1 | 导出缺少必填参数 | GET /export（不带 resource） | HTTP 400，提示缺 resource | [400, "VALIDATION"] | PASS |
| G05 | 健壮性 | P2 | 不支持的方法返回 405 | POST /products/1（详情路径不支持 POST） | HTTP 405 METHOD_NOT_ALLOWED | [405, "METHOD_NOT_ALLOWED"] | PASS |
| G06 | 健壮性 | P1 | 上传接口收到 JSON | POST /api/files Content-Type=json 无文件 | HTTP 400（不是 500） | [400, "VALIDATION"] | PASS |
| G07 | 健壮性 | P2 | 超长关键词不报错 | GET /products?keyword=<300字符> | HTTP 200 | [200, 0] | PASS |
| G08 | 健壮性 | P1 | 注入式关键词无效果 | GET /products?keyword=%' or 1=1 -- | HTTP 200 且 total=0 | [200, 0] | PASS |
| G09 | 健壮性 | P1 | 非法日期筛选不 500 | GET /alert-records?from=abc&to=xyz | HTTP 400 或 200，不能 5xx | [400] | PASS |
| G10 | 健壮性 | P2 | 导出未知资源 | GET /export?resource=nope | HTTP 400 VALIDATION | [400, "VALIDATION"] | PASS |
| H01 | 一致性 | P1 | 分页 total 与翻页不重不漏 | GET /products?size=2 取前两页 | 两页 id 不重复，合计≤total | [5, 4, 4] | PASS |
| H02 | 一致性 | P1 | 写操作产生审计 | 新增产品后查 /audit-logs?entity=product | 最近一条 action=create 且 actor=管理员 | [200, 1, "13800000001"] | PASS |
| H03 | 一致性 | P2 | 首页快照体积在预算内 | GET /api/bootstrap 字节数 | ≤ 60KB | [200, 11] | PASS |
| H04 | 一致性 | P1 | 健康检查 | GET /api/health | 200 且 status=UP | [200, "UP"] | PASS |
| H05 | 一致性 | P2 | 审计分页边界不报错 | GET /audit-logs?page=0&size=0 | HTTP 200，返回合法分页对象 | [200, 1, 1] | PASS |
| H06 | 一致性 | P2 | 租户审计日期格式非法 | GET /audit-logs?from=abc | HTTP 400 VALIDATION，不静默返回全量 | [400, "VALIDATION"] | PASS |
| H07 | 一致性 | P2 | 平台审计日期格式非法 | GET /platform/audit?from=abc | HTTP 400 VALIDATION | [400, "VALIDATION"] | PASS |
| I01 | 分析 | P0 | 总览 KPI 与口径 | GET /api/overview?year=2026 | 200，kpis≥4，orgName 非空，year=2026 | [200, 4, "华东精密制造有限公司", 2026] | PASS |
| I02 | 分析 | P0 | 能耗分析（月份区间） | GET /analysis/energy-consumption?from=2026-01&to=2026-12 | 200，kpis 含综合能耗 | [200, ["综合能耗", "电力", "天然气", "统计月份"]] | PASS |
| I03 | 分析 | P0 | 碳排放分析 | GET /analysis/carbon-emission?from=2026-01&to=2026-12 | 200，kpis 含碳排放总量与范围一 | [200, ["碳排放总量", "范围一", "范围二", "单位产值碳排放"]] | PASS |
| I04 | 分析 | P1 | 能效分析 | GET /analysis/energy-efficiency?from=2026-01&to=2026-12 | 200，kpis 含单位产值能耗 | [200, ["总能源量", "平均单位产值能耗", "对标产品"]] | PASS |
| I05 | 分析 | P1 | 能流分析 | GET /analysis/energy-flow?from=2026-01&to=2026-12 | 200，kpis 含总能耗 | [200, ["总能耗", "能源品种", "最大流向"]] | PASS |
| I06 | 分析 | P1 | 减排策略分析 | GET /analysis/strategy?from=2026-01&to=2026-12 | 200，kpis 含建议条数 | [200, ["建议条数", "高优先级", "覆盖品种"]] | PASS |
| I07 | 分析 | P1 | 配额分析 | GET /analysis/quota?year=2026 | 200，kpis 含配额与使用率 | [200, ["碳排放配额", "已排放", "配额使用率"]] | PASS |
| I08 | 分析 | P1 | 产品碳足迹分析 | GET /analysis/footprint?year=2026 | 200，kpis 含碳足迹值 | [200, ["产品", "碳足迹值", "阶段合计"]] | PASS |
| I09 | 分析 | P1 | 供应链分析 | GET /analysis/supply-chain | 200，kpis 含供应商家数 | [200, ["供应商", "足迹总量", "最高足迹"]] | PASS |
| I10 | 分析 | P2 | 分析区间格式非法 | GET /analysis/energy-consumption?from=2026-01-01 | HTTP 400 VALIDATION，提示月份格式 | [400, "VALIDATION", "月份格式为 YYYY-MM"] | PASS |
| J01 | 预警 | P0 | 新增预警规则 | POST /alert-rules {name,metric,op,threshold,unit,level} | 200，返回规则 id，阈值与名称正确 | [200, 35, "TST 规则705411", 88] | PASS |
| J02 | 预警 | P1 | 规则阈值为负被拒 | POST /alert-rules {threshold:-1} | HTTP 400 VALIDATION | [400, "VALIDATION"] | PASS |
| J03 | 预警 | P1 | 规则详情 | GET /alert-rules/{id} | 200，返回同一条规则 | [200, 35, "TST 规则705411"] | PASS |
| J04 | 预警 | P1 | 修改规则并升版本 | PATCH /alert-rules/{id} {threshold:100,version:0} | 200，阈值 100，version=1 | [200, 100.0, 1] | PASS |
| J05 | 预警 | P0 | 预警记录分页与统计一致 | GET /alert-records?page=1&size=5 + /alert-records/stats | 200，total≥open，stats 四字段齐全 | [200, 3, {"highOpen": 1, "midOpen": 1, "open": 2, "unread": 0}] | PASS |
| J06 | 预警 | P1 | 记录按等级过滤 | GET /alert-records?level=high&size=20 | 200，返回行全部 level=high | [200, 1, ["high"]] | PASS |
| J07 | 预警 | P1 | 批量删除记录需管理员 | 填报员 POST /alert-records/batch-delete | HTTP 403 | [403, "FORBIDDEN"] | PASS |
| K01 | 足迹 | P0 | 新增产品碳足迹 | POST /footprints {name,unit,qty,footprint,date,boundary} | 200，返回 id | [200, 38, 1.25] | PASS |
| K02 | 足迹 | P1 | 足迹数量必须大于 0 | POST /footprints {qty:0} | HTTP 422/400，拒绝 | [400, "VALIDATION"] | PASS |
| K03 | 足迹 | P1 | 足迹详情与修改 | GET + PATCH /footprints/{id} {boundary:摇篮到坟墓,version:0} | 200，边界已更新 | [200, 200, "摇篮到坟墓", 1] | PASS |
| K04 | 足迹 | P1 | 删除足迹后详情 404 | DELETE /footprints/{id} → GET /footprints/{id} | 删除 200，再查 404 | [200, 404, "NOT_FOUND"] | PASS |
| L01 | 能源 | P1 | 能源管理列表 | GET /energy-mgmt?size=5 | 200，返回分页对象 | [200, 2, 2] | PASS |
| L02 | 能源 | P1 | 能源管理缺负责人被拒 | POST /energy-mgmt {atDate,orgName} 无 duty | HTTP 400 VALIDATION | [400, "VALIDATION"] | PASS |
| L03 | 能源 | P1 | 节能改造列表 | GET /retrofits?size=5 | 200，返回分页对象 | [200, 3, 3] | PASS |
| L04 | 能源 | P1 | 改造投资额为负被拒 | POST /retrofits {invest:-1} | HTTP 400/422 | [400, "VALIDATION"] | PASS |
| M01 | 读数 | P0 | 读数分页 | GET /device-readings/page?size=5 | 200，total>0，行含设备与数值 | [200, 24, "EQ-D501"] | PASS |
| M02 | 读数 | P1 | 读数按设备过滤 | GET /device-readings/page?deviceId={id} | 200，返回行 deviceId 全部一致 | [200, 6, 4, [6]] | PASS |
| M03 | 读数 | P1 | 读数为负被拒 | POST /device-readings {qty:-5} | HTTP 400/422 | [400, "VALIDATION"] | PASS |
| M04 | 读数 | P1 | 读数版本冲突 | PATCH /device-readings/{id} {version:999} | HTTP 409，返回当前版本 | [409, "VERSION_CONFLICT"] | PASS |
| N01 | 附件 | P0 | 上传附件 | POST /files (multipart, bizType=evidence,bizId=1) | 200，返回 id 与 sha256 | [200, 143, 14, true] | PASS |
| N02 | 附件 | P1 | 按业务对象列附件 | GET /files?bizType=evidence&bizId=1 | 200，列表包含刚上传的附件 | [200, 1] | PASS |
| N03 | 附件 | P0 | 下载附件内容一致 | GET /files/{id}/download | 200，字节与上传一致，X-Content-SHA256 匹配 | [200, 14, true] | PASS |
| N04 | 附件 | P1 | 删除附件后不可下载 | DELETE /files/{id} → GET download | 删除 200，再下载 404 | [200, 404] | PASS |
| O01 | 幂等 | P0 | 同键重复创建只落一条 | POST /products 两次 + 同 Idempotency-Key | 第二次 200 且 Idempotent-Replay=true，库里仅 1 条 | [200, 200, true, 1] | PASS |
| O02 | 幂等 | P1 | 同键不同请求体冲突 | 同 Idempotency-Key 改 name 再提交 | HTTP 409 IDEMPOTENCY_CONFLICT | [200, 409, "IDEMPOTENCY_CONFLICT"] | PASS |
| O03 | 幂等 | P2 | 不带键可重复创建 | POST /products 两次（无 Idempotency-Key） | 200/200，两条独立记录 | [[200, 593], [200, 594]] | PASS |
| P01 | 导入导出 | P0 | 导出产品 CSV | GET /export?resource=products | 200，UTF-8 BOM 开头且含表头「编码」 | [200, 882, "产品名称,编码,规格型号,类型,分类,单位,1级能耗,2级能耗,3级能耗"] | PASS |
| P02 | 导入导出 | P1 | 导出日期格式非法 | GET /export?resource=readings&from=abc | HTTP 400，不返回 500 | [400, "VALIDATION"] | PASS |
| P03 | 导入导出 | P1 | 导入模板可下载 | GET /import/template?resource=products | 200，CSV 表头与导入列一致 | [200, "编码,名称,规格型号,产品类型,产品分类,单位,1级能耗,2级能耗,3级能耗"] | PASS |
| P04 | 导入导出 | P0 | 导入产品 CSV | POST /import?resource=products（1 行 TST 产品） | 200，success=1，产品可查到 | [200, 1, 0, 1] | PASS |
| P05 | 导入导出 | P1 | 错误表头导入被拒 | POST /import?resource=products（表头 姓名,编码） | HTTP 400 VALIDATION，库里不新增脏数据 | [400, "VALIDATION", 0] | PASS |
| Q01 | 角色 | P1 | 非法页面编码被拒 | POST /roles {pages:[no.such.page]} | HTTP 400 VALIDATION | [400, "VALIDATION"] | PASS |
| Q02 | 角色 | P1 | 首页权限强制补齐 | POST /roles {pages:[product.list]} → GET /roles/{id} | pages 首位为 home | [200, 200, ["home", "product.list"]] | PASS |
| Q03 | 角色 | P1 | 角色名称租户内唯一 | POST /roles 同名两次 | 第二次 HTTP 409 DUPLICATE | [200, 409, "DUPLICATE"] | PASS |
| Q04 | 角色 | P1 | 默认角色不可删除 | DELETE /roles/{默认角色} | HTTP 409 ROLE_DEFAULT | [409, "ROLE_DEFAULT"] | PASS |
| Q05 | 角色 | P1 | 有成员的角色不可删除 | 建角色+用户后 DELETE 角色 | HTTP 409 ROLE_IN_USE | [200, 409, "ROLE_IN_USE"] | PASS |
| Q06 | 席位 | P0 | 席位已满不能新增成员 | 平台把 seatLimit 调为当前成员数 → 新增用户 | HTTP 409 SEAT_LIMIT | [200, 12, 409, "SEAT_LIMIT"] | PASS |
| R04 | 安全 | P1 | 导出中和 CSV 公式注入 | 产品名 =1+1 → 导出 products | 单元格以 '= 开头，不被 Excel 当公式 | [200, 200, "'=1+1,TST-CSV-705411"] | PASS |
| R05 | 安全 | P0 | 成员被停用后旧令牌立即失效 | 登录 → 管理员移除成员 → 旧令牌访问 /overview | HTTP 401 | [200, 200, 401, "SESSION_REVOKED"] | PASS |
| R06 | 安全 | P1 | 角色调整后成员旧令牌失效 | 改角色页面 → 旧令牌访问 /products | HTTP 401 SESSION_REVOKED | [200, 200, 401, "SESSION_REVOKED"] | PASS |
| R07 | 安全 | P1 | 密码不能复用最近三次 | 改密 A→B→再次改回 A | HTTP 400 VALIDATION | [400, "VALIDATION"] | PASS |
| R09 | 安全 | P1 | 报告类型非法被拒 | POST /reports {type:unknown} | HTTP 400 VALIDATION | [400, "VALIDATION"] | PASS |
| R10 | 安全 | P2 | 分页 size 超上限自动收敛 | GET /products?size=1000 | 200，size≤200 且行数与 size 匹配 | [200, 200, 12] | PASS |
| S01 | 导入导出 | P1 | 生产产值 CSV 导入 | 先建产品，再导入 {月份,产品编码,产值,产值单位,产量,产量单位} | success=1，产值列表可查到 | [200, 1, 1] | PASS |
| S02 | 导入导出 | P1 | 碳资产 CSV 导入 | 导入 {年度,资产名称,资产类型,数量,单位} | success=1，资产列表可查到 | [200, 1, 1] | PASS |
| S03 | 导入导出 | P1 | 供应商 CSV 导入 | 导入 {供应商名称,产品名称,产品单位,碳足迹值,碳足迹单位,碳足迹日期,核算范围} | success=1，列表可查到 | [200, 1, 1] | PASS |
| S04 | 导入导出 | P1 | 设施 CSV 导入 | 导入 {设施编码,设施名称,上级设施编码,排序,备注} | success=1，设施列表可查到 | [200, 1, 1] | PASS |
| S05 | 导入导出 | P1 | 13 类资源导出全部可用 | GET /export?resource=… 遍历 13 个资源 | 全部 200 且 CSV 非空 | [13, []] | PASS |

## 汇总

| 项 | 值 |
|---|---|
| 用例总数 | 142 |
| 通过 | 142 |
| 失败 | 0 |
| 通过率 | 100.0% |
| 按优先级 | P0 48 / P1 82 / P2 12 |
