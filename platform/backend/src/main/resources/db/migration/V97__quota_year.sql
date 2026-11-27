-- 年度碳配额：按年维护配额量与下年度预测量，供配额页与首页聚合使用
CREATE TABLE quota_year (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  quota_year INT NOT NULL,
  total_qty NUMERIC(16,4) NOT NULL DEFAULT 0,
  next_year_qty NUMERIC(16,4),
  note VARCHAR(255),
  version INT NOT NULL DEFAULT 0,
  disabled SMALLINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64),
  updated_by VARCHAR(64),
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);
CREATE INDEX idx_quota_year ON quota_year (org_id, quota_year, disabled);

INSERT INTO quota_year (org_id, quota_year, total_qty, next_year_qty, note, version, disabled, created_by, created_at, updated_at)
SELECT org_id, asset_year, SUM(qty), ROUND(SUM(qty) * 0.943), '期初从配额资产导入', 0, 0, 'system', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM carbon_asset
WHERE disabled = 0 AND type = '配额资产' AND asset_year IS NOT NULL
GROUP BY org_id, asset_year;
