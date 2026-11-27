-- V1 初始化：能碳平台切片 1（设备数据 -> 核算 -> 报告）
-- 兼容策略：MySQL 8 语法，H2 用 MODE=MySQL 跑测试（一套 DDL 两处执行）。
-- 表不写 ENGINE/CHARSET，由建库语句统一指定 utf8mb4 + utf8mb4_bin，
-- 这样 H2 不会因 ENGINE 子句报错。审计 JSON 以 MEDIUMTEXT 存储。

CREATE TABLE app_user (
  id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  display_name  VARCHAR(64)  NOT NULL,
  role          VARCHAR(16)  NOT NULL,
  org_id        BIGINT,
  enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_user_username UNIQUE (username)
);

CREATE TABLE org (
  id         BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(200) NOT NULL,
  code       VARCHAR(64)  NOT NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_org_code UNIQUE (code)
);

CREATE TABLE device (
  id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT       NOT NULL,
  name        VARCHAR(120) NOT NULL,
  code        VARCHAR(64)  NOT NULL,
  energy_type VARCHAR(32)  NOT NULL,
  unit        VARCHAR(16)  NOT NULL,
  status      VARCHAR(16)  NOT NULL DEFAULT 'online',
  disabled    BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_device_code UNIQUE (org_id, code),
  CONSTRAINT fk_device_org FOREIGN KEY (org_id) REFERENCES org (id)
);

CREATE TABLE device_reading (
  id                BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id            BIGINT        NOT NULL,
  device_id         BIGINT        NOT NULL,
  ts                DATETIME     NOT NULL,
  qty               NUMERIC(18,4) NOT NULL,
  unit              VARCHAR(16)   NOT NULL,
  rate              NUMERIC(12,4) NOT NULL DEFAULT 1,
  source            VARCHAR(16)   NOT NULL,
  status            VARCHAR(16)   NOT NULL DEFAULT '正常',
  remark            VARCHAR(500),
  version           INT           NOT NULL DEFAULT 0,
  client_request_id VARCHAR(64),
  created_by        VARCHAR(64)   NOT NULL,
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_reading_device FOREIGN KEY (device_id) REFERENCES device (id)
);
CREATE INDEX idx_reading_device_ts ON device_reading (device_id, ts);
CREATE INDEX idx_reading_org_ts ON device_reading (org_id, ts);
CREATE UNIQUE INDEX uk_reading_request ON device_reading (org_id, client_request_id);

CREATE TABLE emission_factor (
  id             BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  category       VARCHAR(32)   NOT NULL,
  fuel           VARCHAR(64)   NOT NULL,
  unit           VARCHAR(32)   NOT NULL,
  factor_value   NUMERIC(18,6) NOT NULL,
  source_doc     VARCHAR(200)  NOT NULL,
  scope          VARCHAR(16)   NOT NULL,
  org_id         BIGINT,
  version        VARCHAR(32)   NOT NULL,
  review_status  VARCHAR(16)   NOT NULL DEFAULT 'pending',
  effective_from DATE          NOT NULL,
  effective_to   DATE,
  created_by     VARCHAR(64),
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_factor_lookup ON emission_factor (fuel, review_status, effective_from);

CREATE TABLE accounting_period (
  id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id       BIGINT        NOT NULL,
  period       VARCHAR(7)    NOT NULL,
  status       VARCHAR(16)   NOT NULL DEFAULT 'draft',
  scope1       NUMERIC(18,4),
  scope2       NUMERIC(18,4),
  total        NUMERIC(18,4),
  calc_version INT           NOT NULL DEFAULT 0,
  version      INT           NOT NULL DEFAULT 0,
  locked_at    DATETIME,
  locked_by    VARCHAR(64),
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_period UNIQUE (org_id, period)
);

CREATE TABLE accounting_line (
  id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  period_id     BIGINT        NOT NULL,
  calc_version  INT           NOT NULL,
  reading_id    BIGINT        NOT NULL,
  device_id     BIGINT        NOT NULL,
  factor_id     BIGINT        NOT NULL,
  scope_type    VARCHAR(8)    NOT NULL,
  activity      NUMERIC(18,4) NOT NULL,
  activity_unit VARCHAR(16)   NOT NULL,
  factor_value  NUMERIC(18,6) NOT NULL,
  emission      NUMERIC(18,4) NOT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_line_period FOREIGN KEY (period_id) REFERENCES accounting_period (id)
);
CREATE INDEX idx_line_period ON accounting_line (period_id, calc_version);

CREATE TABLE report (
  id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  period_id    BIGINT       NOT NULL,
  calc_version INT          NOT NULL,
  file_name    VARCHAR(200) NOT NULL,
  file_size    INT          NOT NULL,
  sha256       VARCHAR(64)  NOT NULL,
  content      LONGBLOB     NOT NULL,
  generated_by VARCHAR(64),
  generated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_report_period FOREIGN KEY (period_id) REFERENCES accounting_period (id)
);
CREATE INDEX idx_report_period ON report (period_id, calc_version);

CREATE TABLE audit_log (
  id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  entity      VARCHAR(64) NOT NULL,
  entity_id   VARCHAR(64) NOT NULL,
  action      VARCHAR(32) NOT NULL,
  before_json MEDIUMTEXT,
  after_json  MEDIUMTEXT,
  actor       VARCHAR(64) NOT NULL,
  logged_at   DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_entity ON audit_log (entity, entity_id, logged_at);

CREATE TABLE period_unlock (
  id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  period_id    BIGINT       NOT NULL,
  reason       VARCHAR(500) NOT NULL,
  requested_by VARCHAR(64)  NOT NULL,
  approved_by  VARCHAR(64)  NOT NULL,
  unlocked_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_unlock_period FOREIGN KEY (period_id) REFERENCES accounting_period (id)
);
CREATE INDEX idx_unlock_period ON period_unlock (period_id, unlocked_at);
