package com.carbonbal.common;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.carbonbal.auth.CurrentUser;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Locale;
import java.util.Set;

/**
 * 租户行级隔离：业务表自动追加 {@code org_id = 当前企业}，插入时自动写入租户。
 * <p>
 * 豁免表说明：
 * <ul>
 *   <li>{@code org}：租户表本身，按 id 查询；</li>
 *   <li>{@code app_user} / {@code user_tenant}：登录阶段尚无租户上下文，且需跨租户查询成员关系；</li>
 *   <li>{@code tenant_role} / {@code tenant_role_page}：登录签发令牌与启动补齐默认角色时无租户上下文，
 *       两表查询均由 RoleService 显式带 {@code org_id}（或按全局唯一的 role_id）过滤；</li>
 *   <li>{@code emission_factor}：国家级/地方级因子 {@code org_id} 为空，由 FactorService 自行处理；</li>
 *   <li>{@code sms_code} / {@code captcha} / {@code login_log} / {@code password_history} / {@code phone_history}：
 *       身份域，无租户列或登录前写入；</li>
 *   <li>{@code revoked_token}：会话吊销表（无 {@code org_id} 列），登出发生在任意租户上下文甚至匿名请求中，
 *       若被追加 {@code org_id} 条件会直接抛"列不存在"。</li>
 * </ul>
 */
public class OrgTenantHandler implements TenantLineHandler {

    private static final Set<String> IGNORE = Set.of(
            "org", "app_user", "user_tenant", "tenant_role", "tenant_role_page", "emission_factor",
            "sms_code", "captcha", "login_log", "password_history", "phone_history",
            "revoked_token",
            "flyway_schema_history"
    );

    @Override
    public Expression getTenantId() {
        return new LongValue(CurrentUser.orgId());
    }

    @Override
    public String getTenantIdColumn() {
        return "org_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return tableName == null || IGNORE.contains(tableName.toLowerCase(Locale.ROOT));
    }
}
