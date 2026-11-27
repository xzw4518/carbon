# 部署手册

> 目标：从一台空服务器到可访问的 HTTPS 站点，含密钥、备份、监控与升级回滚。
> **范围说明**：短信实名通道、域名与 HTTPS 证书由客户环境负责，不在本项目交付范围；本文相关章节仅作接入参考。
> 适用版本：v103 及以后。

## 1. 环境要求

| 项 | 要求 |
|---|---|
| 操作系统 | Linux x86_64（CentOS 7+ / Ubuntu 20.04+）或 Windows Server 2019+ |
| JDK | 17（Temurin / OpenJDK 均可） |
| MySQL | 8.0，字符集 `utf8mb4`，时区 `Asia/Shanghai` |
| 内存 | 应用 ≥ 1 GB，MySQL ≥ 2 GB（演示环境可合并到一台 4 GB 机器） |
| 端口 | 应用 8088（只监听内网），对外 443 由 nginx 终止 TLS |
| 磁盘 | 应用与日志 10 GB；附件按每万份约 1 GB 估算 |

## 2. 首次部署

### 2.1 准备数据库

```sql
CREATE DATABASE carbonbal CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'carbonbal'@'%' IDENTIFIED BY '<强密码>';
GRANT ALL PRIVILEGES ON carbonbal.* TO 'carbonbal'@'%';
FLUSH PRIVILEGES;
```

表结构由 Flyway 在首次启动时自动创建（`V1` → 当前版本）。

### 2.2 生成密钥

```bash
cd platform
bash deploy/gen-secret.sh .env.production
# 输出 64 位随机 JWT_SECRET，写入 .env.production（权限 600）
```

**不要使用任何示例密钥。** 应用启动时会校验：密钥为空、短于 32 位、或包含开发默认值时直接启动失败。

### 2.3 配置环境变量

```bash
export DB_URL='jdbc:mysql://127.0.0.1:3306/carbonbal?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export DB_USER=carbonbal
export DB_PASSWORD='<强密码>'
export JWT_SECRET="$(grep '^JWT_SECRET=' .env.production | cut -d= -f2)"
export SMS_EXPOSE_CODE=false     # 生产必须关闭验证码回显
export JOBS_ENABLED=true
export LOG_FILE=/var/log/carbonbal/carbonbal.log
```

可选：

| 变量 | 默认 | 说明 |
|---|---|---|
| `JWT_TTL_HOURS` | 8 | 令牌有效期 |
| `SMS_FIXED_CODE` | 空 | 留空为随机 6 位；仅演示环境可设固定码 |
| `SEED_ENABLED` | true | 生产首次启动后可设为 false，避免重建演示账号 |
| `LOG_FILE` | `./logs/carbonbal.log` | 日志路径，20 MB 滚动、保留 14 份 |

### 2.4 启动

```bash
java -Xms256m -Xmx1g -jar carbonbal.jar --spring.profiles.active=prod
```

首次启动会自动执行数据库迁移，日志出现 `Successfully applied ... migration` 与 `Started CarbonBalApplication` 即成功。

### 2.5 首次登录与改密

| 账号 | 密码 | 用途 |
|---|---|---|
| `13900000000` | `Superadmin@2026!Pass` | 超级管理员（平台管理 / 租户管理） |
| `13800000001` | `Admin@2026!Pass` | 演示企业管理员 |
| `13800000002` | `Test@2026!Pass` | 普通用户（运营）· 张三 |
| `13800000003` | `Test@2026!Pass` | 普通用户（运营）· 李四 |
| `13800000004` | `Test@2026!Pass` | 普通用户（运营）· 王五 |
| `13800000005` | `Test@2026!Pass` | 普通用户（运营）· 赵六 |

**上线前必须**：用平台账号开通正式租户 → 用返回的临时密码登录 → 强制改密 → 停用或删除演示账号。

## 3. HTTPS

### 3.1 证书

正式环境用 CA 证书；内网/试用可自签：

```bash
bash deploy/gen-selfsigned.sh deploy/certs carbon.example.com
```

### 3.2 nginx

```bash
cp deploy/nginx-https.conf /etc/nginx/conf.d/carbonbal.conf
# 修改 server_name 与证书路径
nginx -t && systemctl reload nginx
```

配置要点：TLS 1.2/1.3、HSTS、`client_max_body_size 12m`（与后端上传上限一致）、健康检查不记日志。

### 3.3 校验

```bash
curl -I https://carbon.example.com/app/
curl -s https://carbon.example.com/api/health
# 期望 {"status":0,"body":{"status":"UP","db":"UP",...}}
```

## 4. 容器化（可选）

```bash
cd platform
export JWT_SECRET="$(grep '^JWT_SECRET=' .env.production | cut -d= -f2)"
docker compose -f deploy/docker-compose.yml up -d --build
```

包含 MySQL 8、应用、数据卷与健康检查。反代仍建议用宿主机 nginx。

## 5. 备份与恢复（已演练）

### 5.1 备份

```bash
export PATH=/usr/local/mysql/bin:$PATH
DB_PASSWORD='<密码>' DB_HOST=127.0.0.1 DB_PORT=3306 \
  bash tools/backup.sh /data/backup 14
```

产出：

- `carbonbal-YYYYmmdd-HHMMSS.sql.gz`（全库，含存储过程）
- `files-YYYYmmdd-HHMMSS.tar.gz`（上传附件）
- 自动清理 14 天前的备份

关键参数（脚本已内置）：`--single-transaction --routines --triggers --no-tablespaces --default-character-set=utf8mb4 --hex-blob`。
**缺少 `--default-character-set` 与 `--hex-blob` 会导致中文与二进制字段恢复失败**（演练时踩过）。

### 5.2 恢复

```bash
DB_PASSWORD='<密码>' bash tools/restore.sh /data/backup/carbonbal-20261125-183536.sql.gz \
  /data/backup/files-20261125-183536.tar.gz
```

脚本会二次确认；恢复完成后重启应用并执行 `bash tools/smoke.sh`。

### 5.3 演练结论（2026-11-25）

在真库（MySQL 8.0.28）上执行：备份 9.0 MB → 恢复到临时库 → **35 张表 / 23 个账号 / 91 份报告 / 24 条读数**，与源库一致。

## 6. 监控

```bash
# 单次探针（异常返回非 0，可挂 cron）
bash tools/monitor.sh http://127.0.0.1:8088 "$ADMIN_TOKEN"

# 每分钟：存活探针
* * * * * cd /opt/carbonbal && bash tools/monitor.sh http://127.0.0.1:8088 "$ADMIN_TOKEN" || \
  curl -s -X POST "<告警 webhook>" -d '{"msg":"carbonbal unhealthy"}'

# 每天 08:10：日巡检（服务/库容量/锁定账号/登录失败/未处理预警/备份新鲜度）
10 8 * * * cd /opt/carbonbal && bash tools/inspect.sh --daily >> /var/log/carbonbal-inspect.log 2>&1

# 每周一 08:30：周巡检（含 29 项数据一致性门禁 + 审计积压）
30 8 * * 1 cd /opt/carbonbal && bash tools/inspect.sh --weekly >> /var/log/carbonbal-inspect.log 2>&1

# 每月 1 日 09:00：月巡检（席位/到期/容量）+ 审计归档（保留 180 天）
0 9 1 * * cd /opt/carbonbal && bash tools/inspect.sh --monthly >> /var/log/carbonbal-inspect.log 2>&1
0 9 1 * * cd /opt/carbonbal && bash tools/archive-audit.sh --days 180 --apply >> /var/log/carbonbal-inspect.log 2>&1

# 每天 02:00：备份（已有条目，保持）
0 2 * * * cd /opt/carbonbal && DB_PASSWORD="$DB_PASSWORD" bash tools/backup.sh ./backup 14
```

巡检脚本只读，不改数据；`archive-audit.sh` 是唯一会搬数据的，先不带 `--apply` 预演。

建议监控项：

| 指标 | 来源 | 阈值 |
|---|---|---|
| 健康状态 | `GET /api/health` 的 `status` / `db` | 任一非 UP 立即告警 |
| 登录失败率 | `login_log` 按小时聚合 | 单 IP > 20 次/小时 |
| 短信发送量 | `sms_code` 按天聚合 | 达日上限 80% 预警（接实名后需关注余额） |
| 磁盘 | 系统 | 使用率 > 85% |
| 备份 | 备份目录最新文件时间 | 超过 26 小时未更新 |
| 数据一致性 | `tools/data-check.sh` | 任一项非 0 |
| 审计积压 | `audit_log` 超 180 天行数 | > 0（执行归档） |
| 容量 | `information_schema` 库容量 | 月增 > 30% 或单表 > 2GB |

## 7. 升级与回滚

### 7.1 升级

```bash
# 1) 备份
bash tools/backup.sh /data/backup 14
# 2) 停服务
systemctl stop carbonbal
# 3) 替换 jar（迁移在启动时自动执行）
cp carbonbal-new.jar /opt/carbonbal/carbonbal.jar
# 4) 启动并验证
systemctl start carbonbal
bash tools/smoke.sh
```

迁移都是增量（加表/加列），不改既有列类型，正常可回退应用版本。

### 7.2 回滚

1. 停服务 → 换回上一个 jar → 启动（数据库保持新版本，旧应用通常兼容）。
2. 若必须回退数据库：用第 5 节恢复备份，再启动旧版本。
3. 回滚后检查：`/api/health`、登录、账号列表、核算列表、报告下载。

## 8. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动报 `JWT_SECRET 未配置或长度不足` | 未注入密钥 | 执行 `deploy/gen-secret.sh` 并 export |
| 启动报 `检测到开发用默认密钥` | 用了示例密钥 | 换成随机密钥 |
| 登录报 423 `账号已停用` | 账号状态或成员关系被停用 | 账号管理里启用，或平台查租户状态 |
| 登录报 423 `企业已停用或到期` | 租户被停用/到期 | 平台端恢复或续期 |
| 未改密访问页面报 403 `MUST_CHANGE_PASSWORD` | 初始密码未改 | 按弹窗改密后重新登录 |
| 备份恢复后中文乱码 | 缺 `--default-character-set=utf8mb4` | 用 `tools/backup.sh` 重新备份 |
| 上传附件 413 | nginx 限制过小 | `client_max_body_size 12m` 并重启 nginx |

## 9. 上线检查清单

- [ ] `JWT_SECRET` 已生成并注入，非示例值
- [ ] `SMS_EXPOSE_CODE=false`，短信通道已接真实通道或已确认演示模式
- [ ] HTTPS 生效，HTTP 301 跳转
- [ ] 演示账号已停用，正式租户已开通并改密
- [ ] 备份定时任务已配置，且成功恢复过一次
- [ ] 监控探针接入告警
- [ ] `bash tools/smoke.sh` 9 项全过
- [ ] 日志路径可写、滚动生效
