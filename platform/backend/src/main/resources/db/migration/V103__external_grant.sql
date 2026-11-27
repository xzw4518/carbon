-- 外部协作者授权 + 平台运营账号
ALTER TABLE app_user ADD COLUMN platform_admin BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE external_grant (
  id             BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  org_id         BIGINT      NOT NULL,
  user_tenant_id BIGINT      NOT NULL,
  scope_type     VARCHAR(16) NOT NULL,
  ref_id         BIGINT      NOT NULL,
  granted_by     VARCHAR(64) NOT NULL,
  expires_at     DATETIME    NULL,
  created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_ext_grant UNIQUE (user_tenant_id, scope_type, ref_id)
);
CREATE INDEX idx_ext_grant_org ON external_grant (org_id, user_tenant_id);
