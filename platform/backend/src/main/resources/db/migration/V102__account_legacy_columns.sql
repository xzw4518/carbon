-- 角色与租户改由 user_tenant 承载，app_user 上的两个旧列保留但允许为空
ALTER TABLE app_user MODIFY COLUMN role VARCHAR(16) NULL;
ALTER TABLE app_user MODIFY COLUMN org_id BIGINT NULL;
