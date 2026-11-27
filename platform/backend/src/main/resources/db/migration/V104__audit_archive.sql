-- 审计归档：audit_log 保留热数据，超保留期搬到 audit_log_archive（由 tools/archive-audit.sh 执行）
-- 归档表结构与 audit_log 一致，追加 archived_at；id 不重新生成，便于回溯。
CREATE TABLE IF NOT EXISTS audit_log_archive (
  id BIGINT NOT NULL,
  entity VARCHAR(64) NOT NULL,
  entity_id VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,
  before_json TEXT NULL,
  after_json TEXT NULL,
  actor VARCHAR(64) NOT NULL,
  logged_at DATETIME NOT NULL,
  org_id BIGINT NULL,
  action_label VARCHAR(64) NULL,
  actor_id BIGINT NULL,
  archived_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

CREATE INDEX idx_archive_entity ON audit_log_archive (entity, entity_id);
CREATE INDEX idx_archive_org ON audit_log_archive (org_id, logged_at);
CREATE INDEX idx_archive_logged ON audit_log_archive (logged_at);
