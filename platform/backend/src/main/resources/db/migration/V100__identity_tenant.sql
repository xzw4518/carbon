-- 用户体系多租户：租户扩列、账号改造、成员关系、短信/图形码、登录流水、审计补租户

-- ---------- 1. 租户（org） ----------
ALTER TABLE org ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active';
ALTER TABLE org ADD COLUMN plan VARCHAR(16) NOT NULL DEFAULT 'standard';
ALTER TABLE org ADD COLUMN seat_limit INT NULL;
ALTER TABLE org ADD COLUMN expire_at DATETIME NULL;
ALTER TABLE org ADD COLUMN suspended_at DATETIME NULL;
ALTER TABLE org ADD COLUMN parent_org_id BIGINT NULL;
ALTER TABLE org ADD COLUMN org_level INT NOT NULL DEFAULT 1;
ALTER TABLE org ADD COLUMN tenant_code VARCHAR(16) NULL;
ALTER TABLE org ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai';
CREATE UNIQUE INDEX uk_org_tenant_code ON org (tenant_code);
CREATE INDEX idx_org_parent ON org (parent_org_id);

-- ---------- 2. 账号（app_user） ----------
ALTER TABLE app_user ADD COLUMN phone VARCHAR(20) NULL;
ALTER TABLE app_user ADD COLUMN email VARCHAR(120) NULL;
ALTER TABLE app_user ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active';
ALTER TABLE app_user ADD COLUMN token_version INT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_user ADD COLUMN password_updated_at DATETIME NULL;
ALTER TABLE app_user ADD COLUMN temp_password_expires_at DATETIME NULL;
ALTER TABLE app_user ADD COLUMN last_org_id BIGINT NULL;
ALTER TABLE app_user ADD COLUMN failed_count INT NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN locked_until DATETIME NULL;
ALTER TABLE app_user ADD COLUMN deleted_at DATETIME NULL;
ALTER TABLE app_user ADD COLUMN password_algo VARCHAR(16) NOT NULL DEFAULT 'bcrypt';
-- 短信登录用户可以没有密码
ALTER TABLE app_user MODIFY COLUMN password_hash VARCHAR(100) NULL;
CREATE UNIQUE INDEX uk_user_phone ON app_user (phone);
CREATE INDEX idx_user_status ON app_user (status);

-- 存量演示账号补手机号（admin/filler/viewer）
UPDATE app_user SET phone = '13800000001' WHERE username = 'admin' AND phone IS NULL;
UPDATE app_user SET phone = '13800000002' WHERE username = 'filler' AND phone IS NULL;
UPDATE app_user SET phone = '13800000003' WHERE username = 'viewer' AND phone IS NULL;
UPDATE app_user SET password_updated_at = created_at WHERE password_updated_at IS NULL;
UPDATE app_user SET last_org_id = org_id WHERE last_org_id IS NULL;

-- ---------- 3. 成员关系（user_tenant） ----------
CREATE TABLE user_tenant (
  id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT      NOT NULL,
  user_id     BIGINT      NOT NULL,
  role        VARCHAR(16) NOT NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'active',
  kind        VARCHAR(16) NOT NULL DEFAULT 'internal',
  data_scope  VARCHAR(16) NOT NULL DEFAULT 'ORG',
  employee_no VARCHAR(32) NULL,
  title       VARCHAR(64) NULL,
  expires_at  DATETIME    NULL,
  invited_by  VARCHAR(64) NULL,
  joined_at   DATETIME    NULL,
  removed_at  DATETIME    NULL,
  version     INT         NOT NULL DEFAULT 0,
  created_by  VARCHAR(64) NULL,
  updated_by  VARCHAR(64) NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_user_tenant UNIQUE (user_id, org_id)
);
CREATE INDEX idx_ut_org_role ON user_tenant (org_id, role, status);

-- 存量账号迁成成员关系
INSERT INTO user_tenant (org_id, user_id, role, status, kind, data_scope, joined_at, created_by, updated_by)
SELECT u.org_id, u.id, u.role, 'active', 'internal', 'ORG', u.created_at, u.username, u.username
FROM app_user u
WHERE u.org_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_tenant t WHERE t.user_id = u.id AND t.org_id = u.org_id);

-- ---------- 4. 短信验证码 ----------
CREATE TABLE sms_code (
  id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  phone           VARCHAR(20) NOT NULL,
  scene           VARCHAR(24) NOT NULL,
  code_hash       VARCHAR(64) NOT NULL,
  attempts        INT         NOT NULL DEFAULT 0,
  expires_at      DATETIME    NOT NULL,
  used_at         DATETIME    NULL,
  ip              VARCHAR(64) NULL,
  provider_msg_id VARCHAR(64) NULL,
  send_status     VARCHAR(16) NOT NULL DEFAULT 'sent',
  error_msg       VARCHAR(255) NULL,
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_sms_phone ON sms_code (phone, scene, created_at);

-- ---------- 5. 图形验证码 ----------
CREATE TABLE captcha (
  id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  captcha_id VARCHAR(64) NOT NULL,
  code_hash  VARCHAR(64) NOT NULL,
  attempts   INT         NOT NULL DEFAULT 0,
  expires_at DATETIME    NOT NULL,
  used_at    DATETIME    NULL,
  ip         VARCHAR(64) NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_captcha UNIQUE (captcha_id)
);

-- ---------- 6. 登录流水 ----------
CREATE TABLE login_log (
  id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id     BIGINT NULL,
  user_id    BIGINT NULL,
  phone      VARCHAR(20) NOT NULL,
  result     VARCHAR(24) NOT NULL,
  channel    VARCHAR(16) NOT NULL DEFAULT 'password',
  ip         VARCHAR(64) NULL,
  user_agent VARCHAR(255) NULL,
  at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_login_phone ON login_log (phone, at);
CREATE INDEX idx_login_org ON login_log (org_id, at);

-- ---------- 7. 密码历史 / 手机号变更历史 ----------
CREATE TABLE password_history (
  id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT      NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_pwd_history UNIQUE (user_id, password_hash)
);

CREATE TABLE phone_history (
  id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  old_phone  VARCHAR(20) NOT NULL,
  new_phone  VARCHAR(20) NOT NULL,
  changed_by VARCHAR(64) NOT NULL,
  changed_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_phone_history_user ON phone_history (user_id, changed_at);

-- ---------- 8. 审计与核算子表补租户 ----------
ALTER TABLE audit_log ADD COLUMN org_id BIGINT NULL;
ALTER TABLE accounting_line ADD COLUMN org_id BIGINT NULL;
ALTER TABLE period_unlock ADD COLUMN org_id BIGINT NULL;
ALTER TABLE audit_log ADD COLUMN action_label VARCHAR(64) NULL;
ALTER TABLE audit_log ADD COLUMN actor_id BIGINT NULL;
CREATE INDEX idx_audit_org ON audit_log (org_id, id);
CREATE INDEX idx_line_org ON accounting_line (org_id, period_id);

UPDATE accounting_line l
SET org_id = (SELECT p.org_id FROM accounting_period p WHERE p.id = l.period_id)
WHERE l.org_id IS NULL;

UPDATE period_unlock u
SET org_id = (SELECT p.org_id FROM accounting_period p WHERE p.id = u.period_id)
WHERE u.org_id IS NULL;

UPDATE audit_log a
SET org_id = (SELECT MIN(u.org_id) FROM app_user u WHERE u.username = a.actor OR u.phone = a.actor)
WHERE a.org_id IS NULL;

-- ---------- 9. 核算四岗流转 ----------
ALTER TABLE accounting_period ADD COLUMN submitted_by VARCHAR(64) NULL;
ALTER TABLE accounting_period ADD COLUMN submitted_at DATETIME NULL;
ALTER TABLE accounting_period ADD COLUMN reviewed_by VARCHAR(64) NULL;
ALTER TABLE accounting_period ADD COLUMN reviewed_at DATETIME NULL;
ALTER TABLE accounting_period ADD COLUMN approved_by VARCHAR(64) NULL;
ALTER TABLE accounting_period ADD COLUMN approved_at DATETIME NULL;
