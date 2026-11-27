# 对照《软件项目开发机制体系 v1.1》的差距审计

> 审计日期：2026-11-25　审计对象：能碳管理平台（Spring Boot + MySQL + 原生前端，多租户）
> 依据：`软件项目开发机制体系_通用版_v1.1_20261123`（§3 六层二十机制、§5.1 场景取用、§9 协作、§10 调研）
> 场景判定：**S3 自建后端** → 全部 P0 + M05/M06/M12/M14/M16；M17 可后置。

## 0. 结论摘要

| 状态 | 数量 | 机制 |
|---|---|---|
| 达标 | 11 | M03、M04、M05、M08、M10、M11、M12、M13、M16、M18、M20 |
| 基本达标 | 6 | M01、M02、M06、M07、M15、M19 |
| 部分达标 | 3 | M09、M14、M17 |
| 缺失 | 0 | — |

初查时最严重的三项不是"没写代码"，而是**文档与实现两套口径**（三项均已在 2026-11-25 第一批修复）：

1. `AGENTS.md` 仍按"原型最小集"登记了 10 条机制豁免（M03/M04/M05/M06/M10/M13/M16…），与现在的多租户后端事实冲突。
2. `发布与回滚.md` 仍写"当前无后端、回滚静态产物、33 路由、UI 门禁 6 项"，实际是 jar + MySQL + 35 路由 + 5 项。
3. 错误码表（附录 E）列了 11 个字符串码，代码里除 `CSRF_INVALID` 外**一个都没落地**，返回的是 HTTP 数字码。

## 1. 逐机制审计

| 机制 | 状态 | 证据 | 缺口 |
|---|---|---|---|
| M01 需求可执行化 | 基本达标 | 10 张需求卡（001–009 + 切片 1）；INDEX 与文件一致；每卡含不做清单与可取证 AC | “不做清单”签字仍靠对话确认；无 AC 形容词自动检查 |
| M02 口径单一真相 | 基本达标 | `docs/字段与口径.md` v2：36 表 P0 字段、8 组双字段/快照、5 个派生指标；data-check 断言 | 非 P0 字段未逐列登记；字典靠评审维护 |
| M03 状态机/权限矩阵 | 达标 | `docs/state-machines.md`（4 张五列表）、`docs/permissions.md`（7 角色 × 22 能力 + 归属/确认列） | — |
| M04 契约与错误码 | 达标 | 字符串 `ErrorCode`（35 码）、`docs/error-codes.md`、信封/过滤器统一、测试按码断言 | 新增码需同步 `http()` 与文档 |
| M05 幂等/并发/事务 | 达标 | 通用 `IdempotencyFilter` + `idempotency_record`（V105）；同键同体重放、同键异体 409、并发占位 409；`IdempotencyTest` 3 条；设备读数保留 `clientRequestId` | 前端 5s 内相同请求体自动复用键；幂等仅覆盖 POST JSON |
| M06 变更安全 | 基本达标 | Flyway 23 个迁移；`docs/release-and-rollback.md` 迁移回滚登记；备份/恢复演练 | 迁移无 down，统一备份恢复；版本回退未演练 |
| M07 渲染边界 | 基本达标 | 页面状态对象、业务主键匹配、请求 seq | 列表勾选一次即整表重绘（可接受，但 M07 建议只改该行）；无焦点/滚动恢复 |
| M08 回填与三处一致 | 达标 | 写后 `bootstrap + render` 重拉；编辑回填真实值；分页写后回当前页 | — |
| M09 交互纪律 | 部分 | 加载/空态、二次确认、键盘可达、移动端 390 | 错误与无权限仍用 toast，无独立错误页；触控尺寸 34px 受限项 |
| M10 非功能预算 | 达标 | `docs/performance-budget.md` + `tools/perf.sh`：10 类阈值，实测 bootstrap 30KB/gzip 4KB、FCP 344ms、16 请求、111KB | 基线为 localhost，未做 4G 与并发压测 |
| M11 分层门禁 | 达标 | L0 gate 11、L1 e2e 101、L2 UI 5、**L3 `data-check.sh` 29 项**、L4 发布 13 项（release-and-rollback） | 仍是多条命令，未做单一 runner；L4 靠清单执行 |
| M12 测试设计 | 达标 | `TST-` 前缀 + `trap` 清理 + 残留回查 0；`purge-test-data.sh` 支持预演/按标记/按手机号；`IdempotencyTest` 覆盖重放与冲突 | 并发竞态未做压测（占位行已覆盖基本场景） |
| M13 四向矩阵 | 达标 | `docs/traceability-matrix.md`：28 个 P0 条目 × 四列，空格有解释 | 需随需求变更同步维护 |
| M14 证据报告 | 部分 | 10 份批次报告、门禁日志、复盘与问题库 | FAIL 未按五类归因模板逐条写；截图证据不完整 |
| M15 发布回滚 | 基本达标 | `docs/release-and-rollback.md`：产物指纹、13 项验证、台账、回滚步骤 | 版本回退未演练；无灰度开关 |
| M16 巡检/数据质量 | 达标 | `inspect.sh` 日/周/月三档；`data-check.sh` 29 项；`V104` 归档表 + `archive-audit.sh`（180 天） | 导出二次确认未做（当前导出均留审计） |
| M17 决策工作台 | 部分 | 首页 KPI + 图表 + 预警 | 无“含数字的结论句”，未接决策建议 |
| M18 复盘闭环 | 达标 | `docs/retrospectives/`：4 份复盘（含 1 份未遂）、13 条行动项全关闭、模板与索引；问题库 12 条 | 90 天复发复查待 2026-12-25；无真实用户复盘 |
| M19 协作协议 | 基本达标 | `docs/collaboration.md`（席位/四要素/四元组/仲裁/打包唯一）、任务卡与交接模板 | write_scopes 靠约定，未做任务系统强制 |
| M20 项目记忆/模板 | 达标 | AGENTS（S3 档位 + R11–R14）、模板库索引 12 项、问题库 12 条、`check-docs.sh` 一致性门禁 | 模板 90 天未使用标记机制未自动化 |

## 2. 文档失效清单（比缺文档更危险）

| 文档 | 现状 | 应为 |
|---|---|---|
| `carbonbal-web/docs/字段与口径.md` | v0，10 字段，引用旧快照键 | 覆盖 36 张业务表关键字段；快照已不含 9 类流水 |
| `carbonbal-web/docs/requirements/INDEX.md` | 表内 3 张卡，实际 6 个文件 | 补 003–005；补近三批需求卡或标注"变更单" |
| `platform/docs/architecture.md` | 称创建接口均支持 clientRequestId | 改为"仅设备读数支持"，或补齐实现 |

## 3. 建议修复顺序

**第一批（P0，先把"契约与证据"补齐，约 1 天）**

1. **M04 错误码**：定字符串码方案（D2 决策）→ `ErrorCode` 增加 code 字段与对照表 → 前端按 code 分支 → e2e 断言 code 而非文案 → 附录 E 扩到业务错误。
2. **M13 四向矩阵**：先覆盖 14 个台账 + 核算 + 报告 + 账号，四列必须同时有值；空格写解释。
3. **M03 五列表 + 矩阵补全**：核算 6 态、账号/成员/邀请 3 态；角色补 reviewer/approver，加"归属/确认方式"。
4. **M06/M15**：为 21 个迁移补 down 或理由；建立发布台账（版本/字节/MD5/门禁），演练一次版本回退。

**第二批（P1，门禁与数据质量，约 1 天）**

5. ~~**M11 L3**~~：已交付 `tools/data-check.sh`（29 项），并入周巡检与发布验证。
6. ~~**M12**~~：已改 `TST-` + `trap` + 残留回查，并清理历史残留（48 账号 / 12 租户 / 25 产品）。
7. ~~**M16**~~：`inspect.sh` 三档 + `archive-audit.sh` + V104。
8. ~~**M10**~~：基线已登记 `performance-budget.md`（localhost，非 4G）。
9. ~~**M05**~~：已实现通用 `Idempotency-Key`。

**第三批（P2，体验与协作）**

10. M09 错误/无权限页；M17 结论句；M18 把三次踩坑写成复盘并落到 AGENTS/门禁；M19/M20 交接与复盘模板。

## 4. 审计假设

- 以仓库内文档与代码为准，未访问生产环境；"发布"指可分享链接与本地 jar，不含客户生产域名。
- 触控 34px、英文缺失（单语后台）视为已登记受限项，不重复计为缺口。
- 本次未审计 §10 开局调研 N0–N8 的过程合规性（项目已进入交付期，无法回溯）。


## 5. 修复进展（2026-11-25 第一批）

- **M04 已落地**：错误码改为字符串业务码；`ApiResponse`/`GlobalExceptionHandler`/`JwtAuthFilter`/`CsrfFilter`/`SecurityConfig` 统一信封；新增 `docs/error-codes.md`；单测按码断言；登录失败实测 `AUTH_BAD_CREDENTIAL`、重复编码 `DUPLICATE`、版本冲突 `VERSION_CONFLICT`、CSRF `CSRF_INVALID`。
- **M13 已落地**：`docs/traceability-matrix.md` 覆盖 28 个 P0 条目。
- **M03 已落地**：`docs/state-machines.md` 四张五列表；`docs/permissions.md` 补 reviewer/approver/external、归属与二次确认列。
- **M06/M15 基本落地**：`docs/release-and-rollback.md` 含产物指纹、发布台账、迁移回滚登记与 10 项验证；`carbonbal-web/docs/发布与回滚.md` 改为前端侧指针。
- **AGENTS.md §2 已切换到 S3 档位**，删除 10 条原型豁免，仅保留 M09 触控等受限项。
- 第二批（P1）已完成：M11-L3、M12、M16、M10、M05（2026-11-25）。

## 6. 修复进展（2026-11-25 第二批）

- **M05**：`IdempotencyFilter` + `idempotency_record`（V105），POST JSON 支持 `Idempotency-Key`；重放/冲突/并发三种分支有测试。
- **M10**：`tools/perf.sh` 14 项预算，`docs/performance-budget.md` 登记基线与阈值。
- **M11**：`tools/data-check.sh` 29 项（双字段/孤儿/唯一性/审计守恒），已并入周巡检与发布 13 项验证。
- **M12**：e2e 改 `TST-` 前缀、trap 清理、残留回查；新增 `purge-test-data.sh`，已清理历史残留（48 账号、12 租户、25 产品、25 凭证、27 设备）。
- **M16**：`tools/inspect.sh` 日/周/月，`V104` + `tools/archive-audit.sh`（保留 180 天），cron 示例进 `deploy.md`。
- 剩余：M18 复盘闭环（等首次真实事故/里程碑）、M17 结论句、M01/M02 文档补齐。

## 7. 修复进展（2026-11-25 第三批）

- **M18 已落地**：`docs/retrospectives/` 4 份复盘（登录拦截、V99 残留、测试数据残留、误删未遂）+ 13 条行动项全部关闭 + 索引与 45 分钟模板。
- **M19 已落地**：`docs/collaboration.md` 定义席位、write_scopes、blocked_by、交接五件套、冲突仲裁、打包唯一；新增任务卡/交接单模板。
- **M20 已落地**：模板库索引 12 项；问题库 12 条（现象→根因→规则→门禁）；`tools/check-docs.sh` 扫描 18 份活文档，0 处过期口径。
- **M01/M02 补齐**：需求卡 006–009 + INDEX 全量；字段口径 v2 覆盖 36 表 P0 字段与双字段真相源。
- **规则沉淀**：AGENTS 增 R11–R14（匿名白名单、clean package、TST 纪律、删除脚本预演）。
