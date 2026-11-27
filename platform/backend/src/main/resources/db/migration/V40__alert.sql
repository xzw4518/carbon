-- V40 预警：规则、事件字典、记录

CREATE TABLE alert_rule (
  id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT        NOT NULL,
  name        VARCHAR(120)  NOT NULL,
  metric      VARCHAR(32)   NOT NULL,
  op          VARCHAR(16)   NOT NULL,
  threshold   DECIMAL(18,4) NOT NULL,
  unit        VARCHAR(16)   NOT NULL,
  cool        INT           NOT NULL DEFAULT 60,
  level       VARCHAR(16)   NOT NULL,
  enabled     TINYINT(1)    NOT NULL DEFAULT 1,
  remark      VARCHAR(300),
  disabled    TINYINT(1)    NOT NULL DEFAULT 0,
  version     INT           NOT NULL DEFAULT 0,
  created_by  VARCHAR(64),
  updated_by  VARCHAR(64),
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_rule_enabled ON alert_rule (org_id, enabled, disabled);

CREATE TABLE alert_event (
  id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT        NOT NULL,
  name        VARCHAR(120)  NOT NULL,
  type        VARCHAR(16)   NOT NULL,
  metric      VARCHAR(32),
  op          VARCHAR(16),
  threshold   DECIMAL(18,4),
  level       VARCHAR(16),
  builtin     TINYINT(1)    NOT NULL DEFAULT 0,
  enabled     TINYINT(1)    NOT NULL DEFAULT 1,
  disabled    TINYINT(1)    NOT NULL DEFAULT 0,
  version     INT           NOT NULL DEFAULT 0,
  created_by  VARCHAR(64),
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_event_type ON alert_event (org_id, type, disabled);

CREATE TABLE alert_record (
  id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id       BIGINT        NOT NULL,
  rule_id      BIGINT,
  title        VARCHAR(200)  NOT NULL,
  level        VARCHAR(16)   NOT NULL,
  metric       VARCHAR(32),
  metric_value DECIMAL(18,4),
  threshold    DECIMAL(18,4),
  unit         VARCHAR(16),
  device_code  VARCHAR(64),
  occurred_at  DATETIME      NOT NULL,
  status       VARCHAR(16)   NOT NULL DEFAULT 'open',
  is_read      TINYINT(1)    NOT NULL DEFAULT 0,
  handled_by   VARCHAR(64),
  handled_at   DATETIME,
  remark       VARCHAR(300),
  disabled     TINYINT(1)    NOT NULL DEFAULT 0,
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_record_time ON alert_record (org_id, occurred_at);
CREATE INDEX idx_record_status ON alert_record (org_id, status, is_read);

INSERT INTO alert_rule (org_id, name, metric, op, threshold, unit, cool, level, created_by) VALUES
  (1, '月度排放超阈值', '排放量', '大于', 1800.0000, 'tCO2e', 1440, '重要', 'admin'),
  (1, '单日能耗突增',   '能耗',   '大于', 60.0000,   'tce',   60,   '一般', 'admin');

INSERT INTO alert_event (org_id, name, type, metric, op, threshold, level, builtin, created_by) VALUES
  (1, '排放超标预警', '碳排放', '排放量', '大于', 1800.0000, '重要', 1, 'admin'),
  (1, '能耗异常预警', '能耗',   '能耗',   '大于', 60.0000,   '一般', 1, 'admin'),
  (1, '设备离线预警', '系统',   '离线时长', '大于', 30.0000, '重要', 1, 'admin');

INSERT INTO alert_record (org_id, rule_id, title, level, metric, metric_value, threshold, unit, device_code, occurred_at, status, is_read, created_at) VALUES
  (1, 1, '月度排放超阈值', 'high', '排放量', 1748.0000, 1800.0000, 'tCO2e', '全厂',     '2026-11-02 08:12', 'open',    0, '2026-11-02 08:12'),
  (1, 2, '单日能耗突增',   'mid',  '能耗',   62.4000,   60.0000,   'tce',   'EQ-A101', '2026-11-08 09:30', 'handled', 1, '2026-11-08 09:30'),
  (1, NULL, '设备离线超 30 分钟', 'mid', '离线时长', 45.0000, 30.0000, '分钟', 'EQ-D501', '2026-11-15 14:05', 'open', 0, '2026-11-15 14:05');
