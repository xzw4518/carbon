-- 账号标识切换为手机号后，username 仅保留用于回滚与兼容，允许为空
ALTER TABLE app_user MODIFY COLUMN username VARCHAR(64) NULL;
