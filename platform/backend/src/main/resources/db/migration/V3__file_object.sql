-- V3 公共能力：文件对象（凭证、报告附件）
-- 内容落本地磁盘，库内只存元数据；生产可改 OSS，接口不变。

CREATE TABLE file_object (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id       BIGINT       NOT NULL,
  name         VARCHAR(200) NOT NULL,
  ext          VARCHAR(16),
  size         BIGINT       NOT NULL,
  sha256       VARCHAR(64)  NOT NULL,
  storage_key  VARCHAR(300) NOT NULL,
  biz_type     VARCHAR(32),
  biz_id       VARCHAR(64),
  uploaded_by  VARCHAR(64),
  disabled     TINYINT(1)   NOT NULL DEFAULT 0,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_file_biz ON file_object (org_id, biz_type, biz_id);
CREATE INDEX idx_file_sha ON file_object (org_id, sha256);
