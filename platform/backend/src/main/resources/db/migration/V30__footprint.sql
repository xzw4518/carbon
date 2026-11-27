-- V30 产品碳足迹：按阶段拆分（原料 / 运输 / 制造），单表三列，便于聚合

CREATE TABLE product_footprint (
  id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id        BIGINT        NOT NULL,
  product_id    BIGINT,
  name          VARCHAR(120)  NOT NULL,
  unit          VARCHAR(16)   NOT NULL,
  qty           DECIMAL(18,4) NOT NULL,
  footprint     DECIMAL(12,4) NOT NULL,
  date          DATE          NOT NULL,
  boundary      VARCHAR(32)   NOT NULL,
  stage_raw     DECIMAL(12,4) NOT NULL DEFAULT 0,
  stage_transport DECIMAL(12,4) NOT NULL DEFAULT 0,
  stage_make    DECIMAL(12,4) NOT NULL DEFAULT 0,
  status        VARCHAR(16)   NOT NULL DEFAULT '已核算',
  disabled      TINYINT(1)    NOT NULL DEFAULT 0,
  version       INT           NOT NULL DEFAULT 0,
  created_by    VARCHAR(64),
  updated_by    VARCHAR(64),
  created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_fp_product FOREIGN KEY (product_id) REFERENCES product (id)
);
CREATE INDEX idx_fp_date ON product_footprint (org_id, date);
CREATE INDEX idx_fp_product ON product_footprint (product_id);

INSERT INTO product_footprint
  (org_id, product_id, name, unit, qty, footprint, date, boundary, stage_raw, stage_transport, stage_make, created_by) VALUES
  (1, 1, '精密轴承座 A 型', 't', 5200.0000, 1.8420, '2026-11-12', '摇篮到大门', 1.1210, 0.2010, 0.5200, 'admin'),
  (1, 2, '铝合金支架 B 型', 't', 3100.0000, 2.1400, '2026-11-08', '摇篮到大门', 1.3500, 0.2300, 0.5600, 'admin'),
  (1, 3, '不锈钢法兰盘',   '万件', 860.0000, 0.9600, '2026-10-27', '摇篮到大门', 0.5800, 0.0900, 0.2900, 'admin'),
  (1, 4, '注塑外壳 C 型',   '万件', 1240.0000, 0.5400, '2026-10-19', '摇篮到坟墓', 0.2900, 0.0600, 0.1900, 'admin'),
  (1, 5, '冷轧钢板组件',   't', 2100.0000, 1.6800, '2026-09-30', '摇篮到大门', 1.0200, 0.1800, 0.4800, 'admin');
