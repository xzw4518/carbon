-- V60 产值管理：产品产值、生产总值
-- 唯一性说明：两张表都带软删（disabled），而 MySQL 的唯一索引不会忽略已删行，
-- 若建 (org_id, period, product_id) 唯一键，删除后无法重建同期间同产品的记录。
-- 因此唯一性在 Service 层按 disabled = 0 校验，库内只建查询索引。

CREATE TABLE production_value (
  id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id       BIGINT        NOT NULL,
  period       VARCHAR(7)    NOT NULL,
  product_id   BIGINT        NOT NULL,
  product_name VARCHAR(120)  NOT NULL,
  output_value DECIMAL(18,4) NOT NULL,
  unit         VARCHAR(16)   NOT NULL,
  qty          DECIMAL(18,4) NOT NULL,
  qty_unit     VARCHAR(16)   NOT NULL,
  disabled     TINYINT(1)    NOT NULL DEFAULT 0,
  version      INT           NOT NULL DEFAULT 0,
  created_by   VARCHAR(64),
  updated_by   VARCHAR(64),
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_pv_period ON production_value (org_id, period, disabled);
CREATE INDEX idx_pv_product ON production_value (org_id, product_id);

CREATE TABLE total_value (
  id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id       BIGINT        NOT NULL,
  period       VARCHAR(7)    NOT NULL,
  name         VARCHAR(120)  NOT NULL,
  qty          DECIMAL(18,4) NOT NULL,
  qty_unit     VARCHAR(16)   NOT NULL,
  output_value DECIMAL(18,4) NOT NULL,
  unit         VARCHAR(16)   NOT NULL,
  disabled     TINYINT(1)    NOT NULL DEFAULT 0,
  version      INT           NOT NULL DEFAULT 0,
  created_by   VARCHAR(64),
  updated_by   VARCHAR(64),
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_tv_period ON total_value (org_id, period, disabled);

INSERT INTO production_value
  (org_id, period, product_id, product_name, output_value, unit, qty, qty_unit, created_by) VALUES
  (1, '2026-11', 1, '精密轴承座 A 型', 620.0000, '万元', 1180.0000, '吨',   'admin'),
  (1, '2026-11', 2, '铝合金支架 B 型', 480.0000, '万元',  760.0000, '吨',   'admin'),
  (1, '2026-10', 1, '精密轴承座 A 型', 598.0000, '万元', 1120.0000, '吨',   'admin'),
  (1, '2026-10', 3, '不锈钢法兰盘',    310.0000, '万元',  420.0000, '吨',   'admin'),
  (1, '2026-09', 2, '铝合金支架 B 型', 452.0000, '万元',  700.0000, '吨',   'admin'),
  (1, '2026-09', 4, '注塑外壳 C 型',   268.0000, '万元',   96.0000, '万件', 'admin');

INSERT INTO total_value
  (org_id, period, name, qty, qty_unit, output_value, unit, created_by) VALUES
  (1, '2026-11', '全厂生产总值', 1120.0000, '吨', 320.0000, '万元', 'admin'),
  (1, '2026-10', '全厂生产总值', 1090.0000, '吨', 306.0000, '万元', 'admin'),
  (1, '2026-09', '全厂生产总值', 1040.0000, '吨', 298.0000, '万元', 'admin');
