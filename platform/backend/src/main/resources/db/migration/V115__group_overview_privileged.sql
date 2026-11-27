-- 集团汇总（group.overview）降为特权页面：默认角色不再自动拥有，需租户管理员显式授予。
-- 原因：RoleService.ensureDefault 历史上每次列表访问都把全部页面补齐到默认角色【运营】，
--       导致普通成员也能进入集团汇总并看到同集团兄弟企业数据（横向越权 P1-1）。
-- 注意：只用单表 DELETE / UPDATE（H2 MODE=MySQL 不支持 MySQL 的多表 DELETE ... JOIN 语法）。
DELETE FROM tenant_role_page
 WHERE page_code = 'group.overview'
   AND role_id IN (SELECT id FROM tenant_role WHERE is_default = 1);

-- 页面集合写在令牌里，升级后强制重新登录；同时闭合"缺 pages 声明的老令牌"窗口（P1-2）。
-- 平台账号（platform_admin=TRUE）不参与业务页面，不 bump，避免影响平台侧会话。
-- 列类型为 BOOLEAN，用 FALSE 而不是 0（H2 与 MySQL 都能识别）。
UPDATE app_user
   SET token_version = COALESCE(token_version, 0) + 1
 WHERE platform_admin = FALSE;
