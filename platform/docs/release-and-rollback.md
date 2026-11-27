# 发布、回滚与版本台账（M15 / M06）

> 单件产物：`carbonbal.jar`（内含前端 `static/app`）。数据库 MySQL 8，迁移 Flyway。
> 原则：版本只向上；发布前备份；回滚优先整包回退；数据库以备份恢复为准，迁移不写 down。

## 1. 产物与版本（当前）

| 项 | 值 |
|---|---|
| 应用版本 | 1.0.0 |
| jar SHA256（前 32 位） | `f90865e2aa04c661d1b20cf41491234b` |
| jar 大小 | 38,903,818 字节 |
| 前端源码指纹（index + assets） | `cb006820d678e5957119d636597c4ede` |
| Flyway 版本 | 115（33 个迁移） |
| 数据库 | MySQL 8.0.28 @ 127.0.0.1:3307 / `carbonbal` |

## 2. 发布前门禁（全部可执行）

| 层 | 命令 | 通过标准 |
|---|---|---|
| 单元/接口 | `mvn test` | 128/128，0 failure |
| L0 静态 | `node tools/gate-l0.cjs` | 11/11 |
| L2 UI | `node tools/check-ui.cjs` | 5/5（35 路由、0 控制台错误） |
| 冒烟 | `bash tools/smoke.sh` | 9/9 |
| 端到端 | `bash tools/e2e.sh` | 102 项（含旧会话登录与测试数据残留回查）；环境性失败须点名（如短信配额） |
| 源码一致 | `bash tools/sync-frontend.sh --check` | 产物与源码一致 |

## 3. 发布步骤

1. 确认版本号（`pom.xml` / `VERSION`），只增不减。
2. `bash tools/sync-frontend.sh` 同步前端产物。
3. 停止服务 → 备份数据库与上传目录：`bash tools/backup.sh`。
4. `mvn -DskipTests package`，记录 jar 的 SHA256 与字节数。
5. 归档上一版 jar 到 `releases/1.0.0/`（不存在则创建）。
6. 启动：`java -jar target/carbonbal.jar --spring.profiles.active=prod`（环境变量见 `README.md`）。
7. 执行第 5 节 15 项验证，把结果写入第 6 节台账。

## 4. 回滚

### 4.1 应用回滚（首选，<5 分钟）

1. 停服务。
2. 用 `releases/<上一版>/carbonbal.jar` 覆盖 `target/carbonbal.jar`。
3. 启动，执行 `/api/health`、登录、列表、一次写操作四项抽查。

### 4.2 数据库回滚

- **策略：备份恢复，不执行 down 迁移。**
- 21 个迁移全部为**加法型**（建表/加列/加索引/种子），无 DROP COLUMN、无破坏性改类型；回滚成本高于前向修复。
- 若新版本写入了不兼容数据，恢复 `tools/backup.sh` 生成的最近备份（含 `--default-character-set=utf8mb4 --hex-blob`），再启动旧版 jar。
- 恢复演练已做：9.0 MB 备份 → 临时库 → 35 表 / 23 账号 / 91 报告 / 24 读数一致（见 `deploy.md` §5.3）。
- **版本回退演练：尚未做**（仅演练过库恢复），列为待办。

### 4.3 迁移回滚登记

| 版本 | 内容 | 回滚方式 | 理由 |
|---|---|---|---|
| V1–V10 | 初始化、种子、主数据 | 备份恢复 | 含种子数据，down 会丢业务数据 |
| V20–V70 | 资产、足迹、文件、预警、能源、产值、凭证 | 备份恢复 | 加法型，前向修复优先 |
| V80–V98 | 企业档案、报告多类型、设施、精度、配额、设备扩展 | 备份恢复 | 含数据修复（rate 精度），不可逆 |
| V100–V103 | 用户体系、多租户、外部授权 | 备份恢复 | 含数据迁移与索引重建 |
| V104 | 审计归档表 | 备份恢复 | 纯新增表与索引 |
| V105 | 幂等记录表 | 备份恢复 | 纯新增表与索引 |
| V106 | 租户授权开始日期 + 状态归一 | 备份恢复 | 加列与数据回填，无删除 |
| V107 | 历史租户席位默认值 | 备份恢复 | 单列数据回填 |
| V108 | 角色表、角色页面、成员层级 | 备份恢复 | 新建 2 表 + 加 2 列 + 数据回填 |

> 新增迁移必须在本表登记；确需 down 的，迁移同批提交 `Vxxx__xxx.down.sql` 并在本表注明。

## 5. 发布后 15 项验证

1. `/api/health` 返回 `status=UP`、`db=UP`
2. `/app/` 登录页可打开，无演示账号提示
3. 管理员密码登录成功
4. 首页 KPI 与 12 月趋势有值
5. 14 个台账各打开一页，`total` 与行数正常
6. 新建一条台账数据 → 列表出现 → 删除
7. 核算期计算 → 提交（或按当前状态）
8. 报告页生成/下载任一报告
9. 导出 CSV 一份，Excel 打开中文正常
10. 移动端 390px 无横向滚动
11. `bash tools/data-check.sh` 29 项全 PASS（双字段/孤儿/重复/审计守恒）
12. `bash tools/perf.sh` 14 项在预算内（阈值见 `performance-budget.md`）
13. `bash tools/inspect.sh --daily` 无 FAIL（WARN 记录并处置）
14. **迁移一致性**：`ls src/main/resources/db/migration | wc -l` 等于
    `unzip -l target/carbonbal.jar 'BOOT-INF/classes/db/migration/*' | grep -c '\.sql'`，
    且 `flyway_schema_history` 最新版本 = 源码最大版本（防 V99 残留复发）
15. `bash tools/check-docs.sh` 0 处过期口径（接口数/断言数/表数/旧 mock 文案）

## 6. 发布台账

| 版本 | 日期 | jar SHA256（前 12） | 字节 | 门禁 | Flyway | 发布人 | 结果/回滚 |
|---|---|---|---|---|---|---|---|
| 1.0.0 | 2026-11-25 | `f90865e2aa04` | 38,903,818 | mvn 58/58 · L0 11/11 · UI 5/5 · smoke 9/9 · e2e 98/100（短信配额） | 103 | 本地 | 运行中 |
| 1.0.1 | 2026-11-25 | `a1c5003243a1` | 38,913,779 | mvn 61/61 · L0 11/11 · UI 5/5 · smoke 9/9 · e2e 100/102 · data 29/29 · perf 14/14 · docs 19/19 | 105 | 本地 | 运行中 |

## 7. 灰度与开关

当前无按租户/人群的灰度开关；新功能随整包发布。需要灰度时，用 `org.plan` 或配置项做维度，默认关闭、变更记审计（待建设）。

## 8. 源码交付包

面向“把源码交给另一方”的场景（非在线发布）：

```bash
bash tools/package-source.sh          # 生成 dist/carbonbal-<版本>.zip / .tar.gz
```

1. 打包前自动执行 `sync-frontend.sh --check`，源码与产物不一致直接失败。
2. 包内排除 `target`、`node_modules`、`backup`、`data`、`logs`、`*.log`，只留源码/迁移/脚本/文档。
3. 包根 `README.md` 为交接说明，`MANIFEST.txt` 记录版本/日期/文件数，`SHA256SUMS` 逐文件校验。
4. 验收：解压到空目录 → `sha256sum -c SHA256SUMS` → `mvn test` 128/128 → 启动登录。
5. 交付时同时给出 zip/tar.gz 两个归档的 SHA256，接收方先校验再解压。
