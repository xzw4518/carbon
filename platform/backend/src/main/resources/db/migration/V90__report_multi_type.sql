-- V90 报告多类型：碳排 / 能源 / 足迹
-- 原 report 表 period_id 为 NOT NULL 且带外键，足迹报告没有核算期，无法复用。
-- 为兼容 MySQL 与 H2 的 ALTER 语法差异，采用「建新表 → 迁数据 → 换名」的一次性迁移；
-- 主键原样保留，审计日志的 entity_id 不受影响。

CREATE TABLE report_v2 (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id       BIGINT       NOT NULL,
  report_type  VARCHAR(16)  NOT NULL DEFAULT 'carbon',
  period_id    BIGINT,
  ref_id       BIGINT,
  period_label VARCHAR(32),
  calc_version INT,
  file_name    VARCHAR(200) NOT NULL,
  file_size    INT          NOT NULL,
  sha256       VARCHAR(64)  NOT NULL,
  content      LONGBLOB     NOT NULL,
  disabled     TINYINT(1)   NOT NULL DEFAULT 0,
  generated_by VARCHAR(64),
  generated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO report_v2
  (id, org_id, report_type, period_id, ref_id, period_label, calc_version,
   file_name, file_size, sha256, content, disabled, generated_by, generated_at)
SELECT id, 1, 'carbon', period_id, period_id, NULL, calc_version,
       file_name, file_size, sha256, content, 0, generated_by, generated_at
FROM report;

UPDATE report_v2
SET period_label = (SELECT p.period FROM accounting_period p WHERE p.id = report_v2.period_id)
WHERE report_type = 'carbon';

DROP TABLE report;
ALTER TABLE report_v2 RENAME TO report;

CREATE INDEX idx_report_org_type ON report (org_id, report_type, id);
CREATE INDEX idx_report_period ON report (period_id, calc_version);
