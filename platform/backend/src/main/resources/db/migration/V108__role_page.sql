-- 角色与页面权限：角色只配置可进入的功能页面
CREATE TABLE tenant_role (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  name VARCHAR(32) NOT NULL,
  remark VARCHAR(200) NULL,
  is_default TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NULL,
  updated_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  disabled TINYINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_tenant_role_org_name ON tenant_role (org_id, name);
CREATE INDEX idx_tenant_role_org ON tenant_role (org_id);

CREATE TABLE tenant_role_page (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  org_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  page_code VARCHAR(64) NOT NULL
);
CREATE UNIQUE INDEX uk_tenant_role_page ON tenant_role_page (role_id, page_code);
CREATE INDEX idx_tenant_role_page_org ON tenant_role_page (org_id);

-- 成员：层级（tenant_admin / user）+ 绑定的角色
ALTER TABLE user_tenant ADD COLUMN user_type VARCHAR(16) NOT NULL DEFAULT 'user';
ALTER TABLE user_tenant ADD COLUMN role_id BIGINT NULL;
CREATE INDEX idx_member_role ON user_tenant (role_id, org_id);

-- 每个企业建默认角色【运营】
INSERT INTO tenant_role (org_id, name, remark, is_default, version, created_by, updated_by, created_at, updated_at, disabled)
SELECT id, '运营', '默认角色：可进入全部业务页面', 1, 0, 'system', 'system', now(), now(), 0 FROM org;

-- 现有成员：租户管理员走层级，其余绑定默认角色
UPDATE user_tenant SET role_id = (SELECT r.id FROM tenant_role r WHERE r.org_id = user_tenant.org_id AND r.is_default = 1);
UPDATE user_tenant SET user_type = 'tenant_admin' WHERE role = 'admin';
UPDATE user_tenant SET role_id = NULL WHERE role = 'admin';
UPDATE user_tenant SET role = 'user' WHERE role <> 'admin';
