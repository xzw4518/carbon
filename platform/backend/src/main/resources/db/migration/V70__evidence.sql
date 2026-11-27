-- V70 碳核查支撑：原始凭证、第三方报告
-- 文件关联：凭证支持多附件，走 file_object(biz_type='evidence', biz_id=凭证ID)；
-- 第三方报告为单文件，业务表存 file_id。两者都只存元数据，内容在本地磁盘/对象存储。
-- 有效期拆成 valid_from / valid_to 两列，避免字符串区间比较。

CREATE TABLE evidence (
  id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT       NOT NULL,
  name        VARCHAR(200) NOT NULL,
  code        VARCHAR(64),
  description VARCHAR(500),
  valid_from  DATE         NOT NULL,
  valid_to    DATE,
  at_date     DATE         NOT NULL,
  disabled    TINYINT(1)   NOT NULL DEFAULT 0,
  version     INT          NOT NULL DEFAULT 0,
  created_by  VARCHAR(64),
  updated_by  VARCHAR(64),
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_evidence_date ON evidence (org_id, at_date, disabled);
CREATE INDEX idx_evidence_code ON evidence (org_id, code);

CREATE TABLE third_party_report (
  id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id      BIGINT       NOT NULL,
  name        VARCHAR(200) NOT NULL,
  description VARCHAR(500),
  file_id     BIGINT,
  valid_from  DATE         NOT NULL,
  valid_to    DATE,
  at_date     DATE         NOT NULL,
  disabled    TINYINT(1)   NOT NULL DEFAULT 0,
  version     INT          NOT NULL DEFAULT 0,
  created_by  VARCHAR(64),
  updated_by  VARCHAR(64),
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_tpr_date ON third_party_report (org_id, at_date, disabled);

INSERT INTO evidence
  (org_id, name, code, description, valid_from, valid_to, at_date, created_by) VALUES
  (1, '2026 年 10 月电费发票', 'INV-2610', '外购电力结算凭证', '2026-10-01', '2026-10-31', '2026-11-02', 'admin'),
  (1, '天然气采购合同',       'CT-2609',  '年度天然气框架合同', '2026-01-01', '2026-12-31', '2026-09-14', 'admin'),
  (1, '设备能效检测报告',     'TS-2608',  '第三方能效检测',     '2026-08-01', '2027-07-31', '2026-08-20', 'admin');

INSERT INTO third_party_report
  (org_id, name, description, valid_from, valid_to, at_date, created_by) VALUES
  (1, '2025 年度碳排放核查报告', '第三方核查机构出具',   '2026-03-01', '2027-02-28', '2026-03-08', 'admin'),
  (1, 'ISO 14064-1 认证证书',    '组织层级温室气体核查', '2025-12-01', '2027-11-30', '2025-12-10', 'admin'),
  (1, '产品碳足迹核查声明',      '精密轴承座 A 型',      '2026-06-01', '2027-05-31', '2026-06-05', 'admin');
