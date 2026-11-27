# 能碳管理平台 1.0.1 交付包

生成时间：2026-11-27 22:28:46 +0800
版本来源：carbonbal-web/VERSION（事实源 platform/tools/lib-facts.sh）
数据库：Flyway 116（34 个迁移）首次启动自动建表；演示数据由 Seeder 生成

> 数据库只交「表结构 + 演示数据」：不包含任何本机历史数据导出，也不需要手工建表或导入 SQL 文件。

## 1. 包清单

| 文件 | 是什么 | 大小 | 给谁用 |
|---|---|---|---|
| carbonbal-1.0.1.zip | 完整源码包：后端（Spring Boot）+ 前端源码 + 数据库迁移 + 测试 + 运维脚本 + 文档，Windows 友好 | 1.2 MB | 甲方开发/运维（Windows） |
| carbonbal-1.0.1.tar.gz | 与 zip 内容相同，保留文件权限与中文文件名，Linux 首选 | 1.0 MB | 甲方开发/运维（Linux） |
| carbonbal-1.0.1-m2-offline.tar.gz | Maven 依赖与插件快照（本机 ~/.m2/repository 的完整副本），内网构建用 | 234.3 MB | 甲方开发/运维，无外网环境 |
| DELIVERY.md | 本文件：部署两条路径、数据库说明、校验方式 | < 10 KB | 甲方开发/运维 |
| SHA256SUMS | 上面各文件的 SHA-256（sha256sum 格式） | < 10 KB | 甲方用于完整性校验 |

源码包顶层目录：`carbonbal-1.0.1/`，内含 `README.md`（部署与账号说明）、`carbonbal-web/`（前端源码，已与后端静态产物一致）、`platform/`（后端、迁移、部署脚本）。

## 2. 部署路径 A：Docker（推荐）

前置：Docker Desktop / Docker Engine 20.10+、docker compose v2。

```bash
tar -xzf carbonbal-1.0.1.tar.gz
cd carbonbal-1.0.1/platform
bash deploy/gen-secret.sh .env.production         # 生成 JWT_SECRET 并写入 .env.production（已存在则不覆盖）
# 编辑 .env.production，补一行：DB_PASSWORD=你的MySQL强密码
docker compose -f deploy/docker-compose.yml up -d --build
```

说明：
- compose 会拉起 MySQL 8.0（utf8mb4）与应用两个容器，应用等 MySQL 健康检查通过后才启动，Flyway 随即自动建表。
- 首次 `--build` 需要能访问 Docker Hub（拉基础镜像）。

启动验证：

```bash
curl http://localhost:8088/api/health        # 期望 {"status":"UP",...}
```

浏览器访问：`http://<服务器IP>:8088/app/`，用源码包 `README.md`「体验账号」一节里的账号登录。

## 3. 部署路径 B：手工部署 + 离线依赖

前置：JDK 17、MySQL 8.0.28+（建库 carbonbal，字符集 utf8mb4）。

```bash
tar -xzf carbonbal-1.0.1.tar.gz
tar -xzf carbonbal-1.0.1-m2-offline.tar.gz                     # 解出 repository/，这就是离线依赖仓库
cd carbonbal-1.0.1/platform/backend
mvn -o -Dmaven.repo.local=<上一步 repository 的绝对路径> clean package -DskipTests
```

运行（按实际环境替换尖括号内容；DB_URL 里有 & 符号，整段用单引号包住）：

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/carbonbal?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USER=carbonbal
export DB_PASSWORD='<MySQL强密码>'
export JWT_SECRET='<用 deploy/gen-secret.sh 生成，或 openssl rand -hex 32>'
java -jar target/carbonbal.jar --spring.profiles.active=prod
```

本次已附依赖快照：按上面命令加 -o 与 -Dmaven.repo.local 即可完全离线构建（发布前已用本快照实测 BUILD SUCCESS）。

能上网时可以省掉 `-o` 与 `-Dmaven.repo.local`，首次构建会从 Maven 中央仓库拉取依赖。国内网络较慢时可在 `~/.m2/settings.xml` 加阿里云镜像：

```xml
<mirror>
  <id>aliyun</id>
  <mirrorOf>central</mirrorOf>
  <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

## 4. 数据库说明

- **表结构**：由 34 个 Flyway 迁移（最高版本 V116）在应用首次启动时自动创建/升级。**不需要手工建表**，也不要在库里手工改表结构——下次启动 Flyway 校验会失败。
- **演示数据**：由 Seeder 生成（配置项 `SEED_ENABLED`，默认 true）。演示结束建议设为 `SEED_ENABLED=false`；Seeder 本身幂等，重复启动不会重复插入。
- **只交表结构 + 演示数据**：包含 0 行本机历史业务数据。
- **密码注入**：数据库密码只能通过 `DB_PASSWORD` 环境变量注入，配置文件里没有默认口令。未注入时应用启动失败属**预期的安全保护**，不是缺陷。
- 版本要求：MySQL 8.0.28+，库与表统一 utf8mb4 / utf8mb4_unicode_ci。

## 5. 初始账号

见源码包内 `README.md` 的「体验账号 / 初始账号」一节（首次启动自动创建）。本文件不重复明文口令。

生产交付后第一件事：改所有演示账号密码、更换 `JWT_SECRET`、把 `SEED_ENABLED` 设为 false。

## 6. 已知注意事项

1. **中文文件名文档**：`carbonbal-web/docs/` 下有 3 个中文名文档（`发布与回滚.md`、`字段与口径.md`、`需求卡模板.md`）。在 Linux 上若使用老版 Info-ZIP `unzip`（6.0x）解压 zip，这 3 个文件名可能变成乱码，进而让 `sha256sum -c SHA256SUMS` 报 3 条 FAILED（**文件内容无损，仅为文件名编码问题**）。建议优先使用 tar.gz，或用 `unzip -O UTF-8`、`python -m zipfile -e` 解压 zip。
2. **JWT_SECRET**：必须用 `deploy/gen-secret.sh` 或 `openssl rand -hex 32` 生成，不要沿用任何示例值；更换后已签发的令牌全部失效（需重新登录）。
3. **端口与健康检查**：默认 8088；`/api/health` 返回 `status=UP` 才算启动完成，容器启动后先等 Flyway 跑完。
4. **演示数据治理**：演示账号与演示数据仅用于验收演示，正式上线前请清理或改密。

## 7. 完整性校验

```bash
sha256sum -c SHA256SUMS          # 每个文件都应输出 OK
```

本目录下每个文件都登记在 SHA256SUMS 中；任一文件校验失败请重新获取交付包，不要使用损坏的文件部署。
