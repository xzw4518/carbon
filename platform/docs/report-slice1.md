# 切片 1 交付报告 · 2026-11-25

## 1. 版本

| 项 | 值 |
|---|---|
| 后端版本 | `1.0.0`（`pom.xml`） |
| 产物 | `target/carbonbal.jar`，36MB（含内嵌前端与依赖） |
| 数据库迁移 | `V1__init.sql`（10 表，MySQL 语法）、`V2__seed.sql`（种子） |
| 运行画像 | dev=H2 2.2.224（MySQL 兼容模式）· prod=MySQL 8.0（utf8mb4_bin） |

## 2. 环境

Windows 11 · JDK 17.0.21（Temurin 便携版）· Maven 3.9.9 · **MySQL 8.0.28（便携实例，端口 3307）** · Chrome headless 1440×950 · 服务端口 8088

## 3. 范围

| 模块 | 文件 |
|---|---|
| 公共 | 信封、错误码、异常、期间锁定守卫 |
| 认证 | JWT、RBAC（admin/filler/viewer）、用户种子 |
| 审计 | 只追加审计，before/after JSON |
| 设备 | 设备列表、读数 CRUD（幂等键 + 乐观锁）、CSV 导入 |
| 因子 | 三级因子、审核、优先级选取 |
| 核算 | 计算引擎、明细、状态机（draft→calculated→submitted→locked→unlock） |
| 报告 | OpenPDF 生成（中文字体 + 机器可读摘要）、SHA256、下载 |
| 前端 | 独立平台页：登录 / 读数 / 核算 / 审计 |

## 4. 矩阵

| 层 | 覆盖 |
|---|---|
| 单元/接口（JUnit + MockMvc） | 10 条断言：读往返、审计前后值、幂等、版本冲突、越权、核算口径、锁定拦截、PDF 三处一致、因子版本、解锁审批 |
| 端到端（curl） | 16 项：登录×2、401、设备列表、核算合计、报告下载+指纹、提交、锁定、锁定拦截、解锁、4 类审计动作 |
| 页面（Playwright） | 登录、读数 6→7 行、计算 7 条明细、报告生成、审计 8 条、控制台 0 错误 |

## 5. 结果

```
mvn test        PASS 10/10   （H2 MySQL 兼容模式）
tools/e2e.sh    PASS 16/16   （真 MySQL 8.0.28，prod profile）
核算示例         scope1 31.9879 + scope2 58.5526 = 90.5405 tCO2e（7 条明细）
报告            97110 字节，sha256 与生成记录一致，PDF 内含 total=90.5405
页面            0 控制台错误（已修 favicon 500 → 404）
```

## 6. 归因（开发中发现的缺陷）

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 1 | Flyway 建表失败 `expected "identifier"` | `value`、`at` 是 H2 保留字 | 改 `factor_value`、`logged_at`、`unlocked_at` |
| 2 | 版本冲突返回旧版本号，客户端无法刷新 | 冲突时返回请求前快照 | 改为重新查询最新版本 |
| 3 | `/favicon.ico` 返回 500 | 兜底异常处理把 404 转成 500 | 加 `NoResourceFoundException` → 404 + data URI 图标 |
| 4 | E2E 脚本首跑 3 项失败 | 断言依赖前一次运行状态 | 改为幂等断言，稳定 16/16 |
| 5 | 打包失败 `Unable to rename jar` | 服务运行中占用 jar | 先停服务再打包（已写入 README 流程） |
| 6 | 切 MySQL 后建表报错 | `BIGSERIAL`/`BYTEA` 是 PG 专有 | 改 `BIGINT AUTO_INCREMENT`/`LONGBLOB`；`TIMESTAMP`→`DATETIME` 规避 2038 |
| 7 | mysqld 启动 abort，datadir 变成 `D://data` | Windows 版 MySQL 无法处理中文路径 | 便携实例移到 `D:/dev-tools`，项目目录不动 |

## 7. 证据

- `target/surefire-reports/`（10 条断言明细）
- `target/e2e-final.out`（16 项端到端）
- `docs/sample-report-page1.png`（PDF 第 1 页渲染）
- `docs/screenshot-accounting.png`、`docs/screenshot-report.png`（页面截图）

## 8. 未覆盖

- MySQL 8.0.28 已实测（迁移、10 条断言、16 项 e2e、大小写行为）；MySQL 8.4/5.7 未测
- 高并发与死锁未压测（乐观锁方案，理论无长事务）
- 报告无 logo、签章、页码
- xlsx 导入、因子批量导入
- 登录失败锁定、密码策略、SSO、多租户隔离
