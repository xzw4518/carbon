-- V116 会话吊销表（BUG-001：退出登录必须真正吊销令牌）。
--
-- 为什么落库而不是放内存 Map：进程重启后内存状态清零，等于"已登出的令牌复活"；
-- 生产重启后攻击者可在令牌剩余有效期内继续访问，这与"用户主动退出"的语义直接冲突。
-- 因此吊销记录必须持久化，由 JwtAuthFilter 每次请求校验。
--
-- 按会话（jti）吊销而非按账号提升 token_version：只让"这台设备/这次登录"下线，
-- 同一账号的其他设备会话保持有效（符合"退出本机"的产品语义）。
--
-- H2（MODE=MySQL）与 MySQL 双兼容约束：
--   1) 单表 DDL，不写 MySQL 专有语法（无多表 DELETE / 无 ON UPDATE CURRENT_TIMESTAMP）；
--   2) CREATE TABLE IF NOT EXISTS，重复执行不报错；
--   3) 索引名全局唯一（H2 的索引名是库级作用域，不能与其他表重名）。

CREATE TABLE IF NOT EXISTS revoked_token (
  id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  jti        VARCHAR(64) NOT NULL,
  user_id    BIGINT,
  org_id     BIGINT,
  phone      VARCHAR(32),
  reason     VARCHAR(32) NOT NULL DEFAULT 'logout',
  expires_at DATETIME    NOT NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 唯一索引同时承担"同一 jti 只落一条"的幂等语义（重复登出不会插入多条）。
CREATE UNIQUE INDEX uk_revoked_token_jti ON revoked_token (jti);
-- 过期清理按 expires_at 扫描，必须建索引，否则定时清理会全表扫。
CREATE INDEX idx_revoked_token_expires ON revoked_token (expires_at);

-- 升级前签发的令牌没有 jti 声明，无法按会话吊销；本次升级统一提版强制重新登录，
-- 保证"线上存在的令牌一定可被吊销"（JwtAuthFilter 对缺 jti 的令牌直接 401）。
-- 平台账号不参与业务会话（且平台侧无租户上下文），与 V115 保持一致不提版。
UPDATE app_user SET token_version = COALESCE(token_version, 0) + 1 WHERE platform_admin = FALSE;
