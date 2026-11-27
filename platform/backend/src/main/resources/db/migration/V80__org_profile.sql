-- V80 企业档案：企业信息页所需字段
-- 逐列 ALTER，兼容 H2(MODE=MySQL) 与 MySQL 8；全部可空，避免影响既有行。

ALTER TABLE org ADD COLUMN short_name VARCHAR(64);
ALTER TABLE org ADD COLUMN org_type VARCHAR(32);
ALTER TABLE org ADD COLUMN industry VARCHAR(64);
ALTER TABLE org ADD COLUMN scale VARCHAR(64);
ALTER TABLE org ADD COLUMN region VARCHAR(128);
ALTER TABLE org ADD COLUMN legal_person VARCHAR(64);
ALTER TABLE org ADD COLUMN reg_date DATE;
ALTER TABLE org ADD COLUMN reg_capital VARCHAR(64);
ALTER TABLE org ADD COLUMN contact VARCHAR(64);
ALTER TABLE org ADD COLUMN phone VARCHAR(64);
ALTER TABLE org ADD COLUMN email VARCHAR(128);
ALTER TABLE org ADD COLUMN intro VARCHAR(1000);
ALTER TABLE org ADD COLUMN remark VARCHAR(500);
ALTER TABLE org ADD COLUMN logo_file_id BIGINT;
ALTER TABLE org ADD COLUMN license_file_id BIGINT;
ALTER TABLE org ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE org ADD COLUMN updated_by VARCHAR(64);
ALTER TABLE org ADD COLUMN updated_at DATETIME;

UPDATE org SET
  short_name = '华东精密',
  org_type = '股份有限公司',
  industry = '金属制品业',
  scale = '中型（480 人）',
  region = '江苏 · 苏州工业园区',
  legal_person = '王建国',
  reg_date = '2019-07-26',
  reg_capital = '8,000 万元',
  contact = '陈立',
  phone = '0512-6688 102',
  email = 'chenli@example.com',
  intro = '金属精密结构件研发与制造，年产能 1.2 万吨。',
  updated_by = 'admin',
  updated_at = CURRENT_TIMESTAMP
WHERE id = 1;
