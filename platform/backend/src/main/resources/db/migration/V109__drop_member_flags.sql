-- V109：移除成员三项配置（成员类型 / 数据范围 / 授权到期）
--
-- 背景：这三项在页面上长期无人使用，且“成员类型=外部”背后是一整套外部协作能力。
-- 产品决策：连同外部门户、授权表一起下线，成员统一按内部员工处理。
--
-- 存量处置：
--   1) 外部账号 → 停用（保留账号与审计，不自动转成可进业务页的内部员工）
--   2) 已过期成员 → 停用（避免删掉到期日后“复活”）
--   3) 数据范围 → 统一 ORG（删除字段后所有人按企业范围）
--   4) external_grant 先归档后删除（发布前已导出 CSV）

UPDATE user_tenant SET status = 'disabled' WHERE kind = 'external';
UPDATE user_tenant SET status = 'disabled'
 WHERE expires_at IS NOT NULL AND expires_at < NOW();
UPDATE user_tenant SET expires_at = NULL WHERE expires_at IS NOT NULL;
UPDATE user_tenant SET data_scope = 'ORG';

-- 逐列删除，兼容 H2（测试）与 MySQL
ALTER TABLE user_tenant DROP COLUMN kind;
ALTER TABLE user_tenant DROP COLUMN data_scope;
ALTER TABLE user_tenant DROP COLUMN expires_at;

DROP TABLE IF EXISTS external_grant;
