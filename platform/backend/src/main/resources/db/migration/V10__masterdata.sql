-- V10 主数据：产品、供应链
-- 通用约定：org_id 隔离、version 乐观锁、disabled 软删

CREATE TABLE product (
  id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT       NOT NULL,
  name        VARCHAR(120) NOT NULL,
  code        VARCHAR(64)  NOT NULL,
  model       VARCHAR(64),
  type        VARCHAR(32)  NOT NULL,
  cls         VARCHAR(32)  NOT NULL,
  unit        VARCHAR(16)  NOT NULL,
  l1          DECIMAL(10,4),
  l2          DECIMAL(10,4),
  l3          DECIMAL(10,4),
  disabled    TINYINT(1)   NOT NULL DEFAULT 0,
  version     INT          NOT NULL DEFAULT 0,
  created_by  VARCHAR(64),
  updated_by  VARCHAR(64),
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_product_code UNIQUE (org_id, code)
);
CREATE INDEX idx_product_type ON product (org_id, type, disabled);

CREATE TABLE supplier (
  id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT        NOT NULL,
  name        VARCHAR(120)  NOT NULL,
  product     VARCHAR(120)  NOT NULL,
  unit        VARCHAR(16)   NOT NULL,
  fp_unit     VARCHAR(32)   NOT NULL,
  footprint   DECIMAL(12,4) NOT NULL,
  date        DATE          NOT NULL,
  target      VARCHAR(32),
  level       VARCHAR(16),
  sort        INT           NOT NULL DEFAULT 0,
  disabled    TINYINT(1)    NOT NULL DEFAULT 0,
  version     INT           NOT NULL DEFAULT 0,
  created_by  VARCHAR(64),
  updated_by  VARCHAR(64),
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_supplier_name UNIQUE (org_id, name, product)
);
CREATE INDEX idx_supplier_level ON supplier (org_id, level, disabled);

INSERT INTO product (org_id, name, code, model, type, cls, unit, l1, l2, l3, created_by) VALUES
  (1, '精密轴承座 A 型', 'BRG-A', 'A-220', '一般产品', '工业产品', '吨', 0.42, 0.38, 0.35, 'admin'),
  (1, '铝合金支架 B 型', 'BRK-B', 'B-110', '低碳产品', '工业产品', '吨', 0.40, 0.36, 0.33, 'admin'),
  (1, '不锈钢法兰盘',   'FLG-C', 'C-80',  '一般产品', '工业产品', '万件', 0.36, 0.32, 0.29, 'admin'),
  (1, '注塑外壳 C 型',   'CASE-C','C-9',   '一般产品', '工业产品', '万件', 0.28, 0.25, 0.23, 'admin'),
  (1, '冷轧钢板组件',   'STL-D', 'D-45',  '零碳产品', '工业产品', '吨', 0.45, 0.41, 0.38, 'admin');

INSERT INTO supplier (org_id, name, product, unit, fp_unit, footprint, date, target, level, sort, created_by) VALUES
  (1, '苏州精铸金属有限公司', '精密轴承座 A 型', '吨', 'tCO2e/t', 1.8420, '2026-11-02', '范围三', '一级', 1, 'admin'),
  (1, '无锡铝业集团',         '铝合金支架 B 型', '吨', 'tCO2e/t', 2.3100, '2026-10-28', '范围三', '一级', 2, 'admin'),
  (1, '常州不锈钢制品厂',     '不锈钢法兰盘',   '万件', 'tCO2e/万件', 0.9600, '2026-10-20', '范围三', '二级', 3, 'admin'),
  (1, '宁波注塑科技有限公司', '注塑外壳 C 型',   '万件', 'tCO2e/万件', 0.5400, '2026-09-30', '范围三', '二级', 4, 'admin');
