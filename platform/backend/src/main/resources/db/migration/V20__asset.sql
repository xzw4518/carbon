-- V20 碳资产：配额/信用资产、交易
-- 交易通过原子扣减保证不超卖：UPDATE ... SET qty = qty - ? WHERE id = ? AND qty >= ?

CREATE TABLE carbon_asset (
  id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id       BIGINT        NOT NULL,
  name         VARCHAR(120)  NOT NULL,
  type         VARCHAR(32)   NOT NULL,
  qty          DECIMAL(18,4) NOT NULL,
  unit         VARCHAR(16)   NOT NULL,
  asset_year   INT           NOT NULL,
  status       VARCHAR(16)   NOT NULL DEFAULT '正常',
  expire_date  DATE,
  disabled     TINYINT(1)    NOT NULL DEFAULT 0,
  version      INT           NOT NULL DEFAULT 0,
  created_by   VARCHAR(64),
  updated_by   VARCHAR(64),
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_asset_name_year UNIQUE (org_id, name, asset_year)
);
CREATE INDEX idx_asset_type ON carbon_asset (org_id, type, disabled);

CREATE TABLE carbon_trade (
  id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id       BIGINT        NOT NULL,
  asset_id     BIGINT        NOT NULL,
  asset_name   VARCHAR(120)  NOT NULL,
  type         VARCHAR(16)   NOT NULL,
  qty          DECIMAL(18,4) NOT NULL,
  price        DECIMAL(18,4) NOT NULL,
  amount       DECIMAL(18,4) NOT NULL,
  party        VARCHAR(120),
  use_purpose  VARCHAR(60),
  trade_date   DATE          NOT NULL,
  status       VARCHAR(16)   NOT NULL DEFAULT '已成交',
  disabled     TINYINT(1)    NOT NULL DEFAULT 0,
  version      INT           NOT NULL DEFAULT 0,
  created_by   VARCHAR(64),
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_trade_asset FOREIGN KEY (asset_id) REFERENCES carbon_asset (id)
);
CREATE INDEX idx_trade_date ON carbon_trade (org_id, trade_date);
CREATE INDEX idx_trade_asset ON carbon_trade (asset_id);

INSERT INTO carbon_asset (org_id, name, type, qty, unit, asset_year, status, created_by) VALUES
  (1, '碳配额（CEA）',        '配额资产', 24500.0000, 'tCO2e', 2026, '正常', 'admin'),
  (1, '碳信用（CCER）',       '信用资产',  3200.0000, 'tCO2e', 2026, '正常', 'admin'),
  (1, '绿电与绿证（RECs）',   '配额资产',  3200.0000, 'tCO2e', 2026, '正常', 'admin');

INSERT INTO carbon_trade (org_id, asset_id, asset_name, type, qty, price, amount, party, use_purpose, trade_date, created_by) VALUES
  (1, 1, '碳配额（CEA）', '购入', 500.0000, 92.4000, 46200.0000, '全国碳市场', '履约', '2026-11-06', 'admin'),
  (1, 2, '碳信用（CCER）', '购入', 200.0000, 68.5000, 13700.0000, '北京绿交所', '抵消', '2026-11-10', 'admin'),
  (1, 1, '碳配额（CEA）', '售出', 120.0000, 95.8000, 11496.0000, '全国碳市场', '盈余处置', '2026-11-18', 'admin');
