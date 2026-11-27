-- V50 节能管理：能源管理情况、节能改造

CREATE TABLE energy_mgmt (
  id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT       NOT NULL,
  at_date     DATE         NOT NULL,
  org_name    VARCHAR(120) NOT NULL,
  duty        VARCHAR(64)  NOT NULL,
  team        VARCHAR(200),
  post        VARCHAR(64),
  charge      VARCHAR(64),
  filler      VARCHAR(64),
  contact     VARCHAR(64),
  system_name VARCHAR(120),
  report      VARCHAR(16),
  filing      VARCHAR(200),
  inst_name   VARCHAR(120),
  stat        VARCHAR(200),
  promo       VARCHAR(200),
  training    VARCHAR(200),
  subsidy     VARCHAR(16),
  disabled    TINYINT(1)   NOT NULL DEFAULT 0,
  version     INT          NOT NULL DEFAULT 0,
  created_by  VARCHAR(64),
  updated_by  VARCHAR(64),
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_mgmt_year UNIQUE (org_id, at_date)
);
CREATE INDEX idx_mgmt_org ON energy_mgmt (org_id, disabled);

CREATE TABLE retrofit (
  id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id       BIGINT        NOT NULL,
  project_year INT           NOT NULL,
  name         VARCHAR(200)  NOT NULL,
  invest       DECIMAL(18,4) NOT NULL,
  invest_unit  VARCHAR(16)   NOT NULL,
  saving       DECIMAL(18,4) NOT NULL,
  saving_unit  VARCHAR(16)   NOT NULL,
  contract     VARCHAR(32),
  done         VARCHAR(16)   NOT NULL DEFAULT '否',
  at_date      DATE,
  disabled     TINYINT(1)    NOT NULL DEFAULT 0,
  version      INT           NOT NULL DEFAULT 0,
  created_by   VARCHAR(64),
  updated_by   VARCHAR(64),
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_retrofit_year ON retrofit (org_id, project_year, disabled);

INSERT INTO energy_mgmt
  (org_id, at_date, org_name, duty, team, post, charge, filler, contact, system_name, report, filing, inst_name, stat, promo, training, subsidy, created_by) VALUES
  (1, '2026-01-15', '设备动力部', '李国强', '设备、生产、财务三方组成', '能源管理岗', '陈立', '陈立', '0512-6688 102',
   'ISO 50001', '是', '已报设备动力部备案', '能源管理制度', '按月统计，计量器具已检定', '季度节能宣传', '年度节能培训', '否', 'admin'),
  (1, '2025-01-12', '设备动力部', '王强', '设备、生产两方组成', '能源管理岗', '陈立', '陈立', '0512-6688 108',
   '制度已建立未认证', '是', '已报设备动力部备案', '能源管理制度', '按月统计，计量器具已检定', '季度节能宣传', '年度节能培训', '否', 'admin');

INSERT INTO retrofit
  (org_id, project_year, name, invest, invest_unit, saving, saving_unit, contract, done, at_date, created_by) VALUES
  (1, 2026, '空压系统余热回收', 128.0000, '万元', 186.5000, 'tce', 'EMC', '是', '2026-06-30', 'admin'),
  (1, 2026, '照明 LED 改造',     36.0000, '万元',  48.2000, 'tce', '自投', '是', '2026-08-15', 'admin'),
  (1, 2025, '电机变频改造',      85.0000, '万元', 120.0000, 'tce', 'EMC', '否', '2026-12-31', 'admin');
