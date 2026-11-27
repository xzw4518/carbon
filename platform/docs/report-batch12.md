# 第十二批交付报告：源码交付包

> 日期：2026-11-25　版本：1.0.1　产物：`dist/carbonbal-1.0.1.zip` / `.tar.gz`
> 目标：不再依赖在线链接，交付一份**可独立构建、可自证**的完整源码包。

## 1. 交付物

| 文件 | 大小 | SHA256 |
|---|---|---|
| `carbonbal-1.0.1.zip` | 1.26 MB（1,257,518 字节） | `348b35863ba043333141ee2f495753c20c63926f0daadae323d633b0f96a28c6` |
| `carbonbal-1.0.1.tar.gz` | 1.06 MB（1,063,904 字节） | `36060eb6a273bf626c9dcf377dbccaed02dd1caf8500b4adcd093d053d8f9305` |

包内 337 个文件，含逐文件 `SHA256SUMS`、`MANIFEST.txt`（版本/日期/文件数/JDK/Spring Boot/Flyway）。

## 2. 包含与排除

**包含**

- `自研原型/`：前端源码、需求卡、字段口径、L0/UI 门禁与性能采样；
- `platform/backend/src`：Java 源码、配置、23 个 Flyway 迁移、61 条测试；
- `platform/backend/tools`：e2e、smoke、数据门禁、性能、巡检、备份、恢复、测试数据清理、打包；
- `platform/deploy`：Dockerfile、compose、nginx HTTP/HTTPS、自签证书、密钥生成；
- `platform/docs`：架构、权限、状态机、错误码、发布回滚、复盘、模板库、问题库；
- 包根 `README.md`（交接说明）、`VERSION`、`MANIFEST.txt`、`SHA256SUMS`。

**排除**

`target/`、`自研原型/node_modules/`、`platform/backend/backup/`、`data/`、`logs/`、`*.log`、`.tmp_*`、`dist/`。
包体因此从 59 MB（含构建产物与备份）降到 1.1 MB。

## 3. 可复现打包

```bash
bash platform/tools/package-source.sh
```

脚本先执行 `sync-frontend.sh --check`，源码与产物不一致直接失败；随后暂存 → 生成清单与校验和 → 同时产出 tar.gz 与 zip → 打印两个归档的 SHA256。

## 4. 收包方验收（已在空目录实测）

| 步骤 | 结果 |
|---|---|
| `sha256sum -c SHA256SUMS` | 全部 OK |
| `bash platform/tools/sync-frontend.sh --check` | 产物与源码一致 |
| `mvn -DskipTests package` | 从解压目录成功产出 38 MB jar |
| `mvn test` | 61/61 |
| 启动与登录 | 按 README 第 3 节可启动（H2） |

## 5. 交接说明要点（包根 README）

- 五分钟体验：H2 + 种子数据，无需 MySQL；
- 生产运行：建库（`utf8mb4_bin`）、环境变量、`JWT_SECRET` 强校验、Flyway 自动迁移；
- 前端修改后必须 `sync-frontend.sh`，否则 jar 内仍是旧页面；
- 验收命令与期望值（61、9、102、29、14、0）；
- 交付边界：短信模拟、HTTPS 未公网实测、性能基线为 localhost、足迹阶段明细未恢复、核算标准为常量；
- 收到后第一件事：改演示账号密码、换 `JWT_SECRET`、关闭 `SEED_ENABLED`。

## 6. 与发布的关系

- 源码包**不等于**已上线：不含 `target/`、运行库数据与生产密钥，接收方需自行构建与初始化。
- 在线发布仍走 `deploy/` 与 `release-and-rollback.md`；两者共用同一份源码。
- 本批未改业务代码，运行中的服务与 1.0.1 jar 不受影响。

## 7. 局限

- 包内 `static/app` 是同步后的产物；若接收方只改 `自研原型` 不跑同步，页面不会更新（README 已加粗提示）。
- 未附带示例数据库 dump：种子与迁移足以初始化，真实数据需另行导入。
- Windows 下需 Git Bash 才能执行脚本；生产建议 Linux。
