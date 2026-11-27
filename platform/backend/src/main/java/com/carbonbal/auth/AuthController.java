package com.carbonbal.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbonbal.audit.AuditService;
import com.carbonbal.common.ApiResponse;
import com.carbonbal.common.BizException;
import com.carbonbal.common.ErrorCode;
import com.carbonbal.common.Hashes;
import com.carbonbal.identity.CaptchaService;
import com.carbonbal.identity.PasswordHistory;
import com.carbonbal.identity.PasswordHistoryMapper;
import com.carbonbal.identity.PhoneHistory;
import com.carbonbal.identity.PhoneHistoryMapper;
import com.carbonbal.identity.SmsService;
import com.carbonbal.identity.UserTenant;
import com.carbonbal.identity.RoleService;
import com.carbonbal.identity.UserTenantMapper;
import com.carbonbal.org.Org;
import com.carbonbal.org.OrgMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AppUserMapper users;
    private final UserTenantMapper members;
    private final PasswordHistoryMapper passwordHistory;
    private final PhoneHistoryMapper phoneHistory;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final JwtService jwt;
    private final LoginAttemptService loginAttempts;
    private final AuthStateService state;
    private final TokenRevocationService revocation;
    private final SmsService sms;
    private final CaptchaService captcha;
    private final OrgMapper orgs;
    private final AuditService audit;
    private final RoleService roleService;
    private final boolean cookieMode;
    private final boolean cookieSecure;
    private final int ttlHours;
    /** 可信反向代理白名单；只有 remoteAddr 命中才解析 X-Forwarded-For。 */
    private final java.util.Set<String> trustedProxies;
    /** 「账号」维度失败阈值：防轮换 XFF 绕过 IP 维度锁定。 */
    private final int maxAttemptsAccount;
    /** 「账号 + IP」维度阈值，与 LoginAttemptService 默认值保持一致。 */
    private final int maxAttempts;

    public AuthController(AppUserMapper users, UserTenantMapper members, PasswordHistoryMapper passwordHistory,
                          PhoneHistoryMapper phoneHistory, PasswordEncoder encoder, PasswordPolicy policy,
                          JwtService jwt, LoginAttemptService loginAttempts, AuthStateService state,
                          TokenRevocationService revocation,
                          SmsService sms, CaptchaService captcha, OrgMapper orgs, AuditService audit, RoleService roleService,
                          @Value("${app.auth.cookie.enabled:true}") boolean cookieMode,
                          @Value("${app.auth.cookie.secure:false}") boolean cookieSecure,
                          @Value("${app.jwt.ttl-hours:8}") int ttlHours,
                          @Value("${app.auth.trusted-proxies:}") String trustedProxies,
                          @Value("${app.auth.max-attempts:5}") int maxAttempts,
                          @Value("${app.auth.max-attempts-account:20}") int maxAttemptsAccount) {
        this.users = users;
        this.members = members;
        this.passwordHistory = passwordHistory;
        this.phoneHistory = phoneHistory;
        this.encoder = encoder;
        this.policy = policy;
        this.jwt = jwt;
        this.loginAttempts = loginAttempts;
        this.state = state;
        this.revocation = revocation;
        this.sms = sms;
        this.captcha = captcha;
        this.orgs = orgs;
        this.audit = audit;
        this.roleService = roleService;
        this.cookieMode = cookieMode;
        this.cookieSecure = cookieSecure;
        this.ttlHours = ttlHours;
        this.trustedProxies = parseProxies(trustedProxies);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.maxAttemptsAccount = Math.max(1, maxAttemptsAccount);
    }

    /** 解析可信代理白名单：逗号分隔，空串=不信任任何代理（默认拒绝 XFF）。 */
    private static java.util.Set<String> parseProxies(String raw) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /* ==================== 密码登录 ==================== */

    @PostMapping("/login")
    public ApiResponse login(@RequestBody AuthDtos.LoginReq req, HttpServletRequest request,
                             HttpServletResponse response) {
        String account = req.account();
        if (account.isBlank()) throw BizException.validation("请输入手机号");
        if (req.password() == null || req.password().isBlank()) throw BizException.validation("请输入密码");
        String ip = clientIp(request);
        String ipKey = "ip|" + account + "|" + ip;
        String acctKey = "acct|" + account;
        loginAttempts.check(ipKey, maxAttempts);
        loginAttempts.check(acctKey, maxAttemptsAccount);
        // 密码登录同样要求图形验证码，挡住撞库与脚本爆破；放在账号查询之前，避免探测账号是否存在
        captcha.verifyLogin(req.captchaId(), req.captchaCode(), ip);

        AppUser u = findByAccount(account);
        String fail = checkAccount(u, req.password(), ip);
        if (fail != null) {
            loginAttempts.fail(ipKey, maxAttempts);
            loginAttempts.fail(acctKey, maxAttemptsAccount);
            state.logLogin(u == null ? null : u.getOrgId(), u == null ? null : u.getId(),
                    account, fail, "password", ip, request.getHeader("User-Agent"));
            throw loginError(fail, u);
        }
        loginAttempts.success(ipKey);
        loginAttempts.success(acctKey);
        return finishLogin(u, req.orgId(), "password", ip, request, response);
    }

    /* ==================== 短信验证码登录 ==================== */

    @GetMapping("/captcha")
    public ApiResponse captcha(HttpServletRequest request) {
        if (!captcha.enabled()) {
            return ApiResponse.ok(Map.of("enabled", false));
        }
        CaptchaService.Challenge c = captcha.create(clientIp(request));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("captchaId", c.captchaId());
        m.put("image", c.image());
        return ApiResponse.ok(m);
    }

    @PostMapping("/sms/send")
    public ApiResponse sendSms(@RequestBody AuthDtos.SmsSendReq req, HttpServletRequest request) {
        captcha.verify(req.captchaId(), req.captchaCode(), clientIp(request));
        String demoCode = sms.send(req.phone().trim(), req.scene(), clientIp(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sent", true);
        out.put("message", "验证码已发送");
        if (demoCode != null) out.put("demoCode", demoCode);
        return ApiResponse.ok(out);
    }

    @PostMapping("/sms/login")
    public ApiResponse smsLogin(@Valid @RequestBody AuthDtos.SmsLoginReq req, HttpServletRequest request,
                                HttpServletResponse response) {
        String phone = req.phone().trim();
        String ip = clientIp(request);
        String ipKey = "ip|" + phone + "|" + ip;
        String acctKey = "acct|" + phone;
        loginAttempts.check(ipKey, maxAttempts);
        loginAttempts.check(acctKey, maxAttemptsAccount);
        AppUser u = findByAccount(phone);
        if (u == null || u.getDeletedAt() != null) {
            state.logLogin(null, null, phone, "bad_code", "sms", ip, request.getHeader("User-Agent"));
            loginAttempts.fail(ipKey, maxAttempts);
            loginAttempts.fail(acctKey, maxAttemptsAccount);
            throw new BizException(ErrorCode.AUTH_CODE_INVALID, "手机号或验证码错误");
        }
        try {
            sms.verify(phone, "login", req.code());
        } catch (BizException e) {
            state.logLogin(u.getOrgId(), u.getId(), phone, "bad_code", "sms", ip, request.getHeader("User-Agent"));
            loginAttempts.fail(ipKey, maxAttempts);
            loginAttempts.fail(acctKey, maxAttemptsAccount);
            throw e;
        }
        String disabled = accountBlocked(u);
        if (disabled != null) {
            state.logLogin(u.getOrgId(), u.getId(), phone, disabled, "sms", ip, request.getHeader("User-Agent"));
            throw loginError(disabled, u);
        }
        loginAttempts.success(ipKey);
        loginAttempts.success(acctKey);
        return finishLogin(u, req.orgId(), "sms", ip, request, response);
    }

    /* ==================== 选择租户 ==================== */

    @PostMapping("/tenant/select")
    public ApiResponse selectTenant(@Valid @RequestBody AuthDtos.TenantSelectReq req,
                                    HttpServletRequest request, HttpServletResponse response) {
        Claims c;
        try {
            c = jwt.parse(req.tempToken());
        } catch (Exception e) {
            throw new BizException(ErrorCode.AUTH_TOKEN_INVALID, "临时令牌已失效，请重新登录");
        }
        if (!"tenant-select".equals(String.valueOf(c.get("scope")))) {
            throw new BizException(ErrorCode.AUTH_TOKEN_INVALID, "临时令牌无效，请重新登录");
        }
        Long userId = asLong(c.get("uid"));
        AppUser u = userId == null ? null : users.selectById(userId);
        if (u == null) throw new BizException(ErrorCode.AUTH_BAD_CREDENTIAL, "账号不存在");
        UserTenant t = activeMember(req.orgId(), u.getId());
        if (t == null) throw BizException.notFound("你不在该企业中");
        assertTenantUsable(t.getOrgId());
        return issue(u, t, request, response);
    }

    /** 已登录状态下切换企业：校验成员关系后签发新令牌。 */
    @PostMapping("/switch-tenant")
    public ApiResponse switchTenant(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        Long uid = CurrentUser.userIdOrNull();
        AppUser u = uid == null ? null : users.selectById(uid);
        if (u == null) throw new BizException(ErrorCode.AUTH_TOKEN_INVALID, "会话失效，请重新登录");
        Long orgId = asLong(body.get("orgId"));
        if (orgId == null) throw BizException.validation("请选择企业");
        UserTenant t = activeMember(orgId, u.getId());
        if (t == null) throw BizException.notFound("你不在该企业中");
        assertTenantUsable(orgId);
        return issue(u, t, null, response);
    }

    /* ==================== 当前用户 ==================== */

    @GetMapping("/me")
    public ApiResponse me() {
        Long uid = CurrentUser.userIdOrNull();
        AppUser u = uid != null ? users.selectById(uid)
                : users.selectOne(Wrappers.<AppUser>lambdaQuery()
                        .eq(AppUser::getPhone, CurrentUser.username()).last("LIMIT 1"));
        if (u == null) throw new BizException(ErrorCode.AUTH_TOKEN_INVALID, "会话失效，请重新登录");
        boolean impersonating = CurrentUser.impersonating();
        // 代管时按租户上下文返回角色，而不是平台账号身份
        boolean platform = Boolean.TRUE.equals(u.getPlatformAdmin()) && !impersonating;
        AuthStateService.MemberState m = platform ? null : state.member(CurrentUser.orgId(), u.getId());
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", u.getId());
        user.put("username", u.getUsername() == null ? u.getPhone() : u.getUsername());
        user.put("phone", Hashes.maskPhone(u.getPhone()));
        user.put("phoneRaw", u.getPhone());
        user.put("displayName", u.getDisplayName());
        user.put("email", u.getEmail());
        user.put("role", platform ? "platform_admin" : (m == null ? CurrentUser.role() : m.role()));
        user.put("orgId", platform ? 0 : CurrentUser.orgId());
        user.put("orgName", platform ? "平台管理"
                : (impersonating ? orgNameOf(CurrentUser.orgId()) : (m == null ? "" : m.orgName())));
        user.put("impersonating", impersonating);
        user.put("platformAdmin", platform);
        String tierRole = m == null ? CurrentUser.role() : m.role();
        user.put("userType", platform || impersonating || "admin".equals(tierRole) ? "tenant_admin"
                : (m == null ? "user" : m.userType()));
        user.put("roleId", m == null ? null : m.roleId());
        String meType = String.valueOf(user.get("userType"));
        user.put("roleName", "tenant_admin".equals(meType) ? "租户管理员"
                : (m == null || m.roleId() == null ? "" : roleService.nameOf(m.roleId())));
        user.put("pages", platform ? List.of() : CurrentUser.pages());
        // 到期提醒：站内横幅用，平台账号没有租户授权期
        if (!platform) putLicense(user, CurrentUser.orgId());
        user.put("mustChangePassword", Boolean.TRUE.equals(u.getMustChangePassword()));
        String role = platform ? "platform_admin" : (m == null ? CurrentUser.role() : m.role());
        AuthDtos.MeResp resp = new AuthDtos.MeResp(user, tenantsOf(u.getId()),
                platform ? 0 : CurrentUser.orgId(), role, permissions(role),
                policy.expireInDays(u.getPasswordUpdatedAt()));
        return ApiResponse.ok(resp);
    }

    @GetMapping("/password-policy")
    public ApiResponse passwordPolicy() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rules", policy.rules());
        m.put("expireDays", policy.expireDays());
        return ApiResponse.ok(m);
    }

    @PostMapping("/password/change")
    public ApiResponse changePassword(@Valid @RequestBody AuthDtos.ChangePwdReq req) {
        Long uid = CurrentUser.userIdOrNull();
        AppUser u = uid == null ? null : users.selectById(uid);
        if (u == null) throw new BizException(ErrorCode.AUTH_TOKEN_INVALID, "会话失效，请重新登录");
        if (u.getPasswordHash() == null || !encoder.matches(req.oldPassword(), u.getPasswordHash())) {
            throw BizException.validation("原密码不正确");
        }
        if (req.oldPassword().equals(req.newPassword())) {
            throw BizException.validation("新密码不能与原密码相同");
        }
        applyNewPassword(u, req.newPassword());
        return ApiResponse.ok(Map.of("changed", true));
    }

    /**
     * 超级管理员进入租户：签发代管令牌（该租户内 role=admin，imp=true）。
     * 不受租户状态、成员关系、职责分离限制；操作按租户 org_id 留痕。
     */
    @PostMapping("/enter-tenant")
    public ApiResponse enterTenant(@RequestBody Map<String, Object> body,
                                   HttpServletRequest request, HttpServletResponse response) {
        Long uid = CurrentUser.userIdOrNull();
        AppUser u = uid == null ? null : users.selectById(uid);
        if (u == null || !Boolean.TRUE.equals(u.getPlatformAdmin())) {
            throw BizException.forbidden("仅超级管理员可以进入租户");
        }
        Long orgId = asLong(body.get("orgId"));
        if (orgId == null) throw BizException.validation("请选择租户");
        Org o = orgs.selectById(orgId);
        if (o == null) throw new BizException(ErrorCode.TENANT_NOT_FOUND, "租户不存在");

        String token = jwt.issue(u, orgId, "admin", "*", true);
        if (cookieMode) setAuthCookies(response, token);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", u.getId());
        user.put("username", u.getUsername() == null ? u.getPhone() : u.getUsername());
        user.put("phone", Hashes.maskPhone(u.getPhone()));
        user.put("phoneRaw", u.getPhone());
        user.put("displayName", u.getDisplayName());
        user.put("role", "admin");
        user.put("orgId", orgId);
        user.put("orgName", o.getName());
        user.put("impersonating", true);
        user.put("platformAdmin", true);
        user.put("userType", "tenant_admin");
        user.put("pages", List.of("*"));
        user.put("mustChangePassword", Boolean.TRUE.equals(u.getMustChangePassword()));
        putLicense(user, orgId);
        audit.record("tenant", orgId, "enter", null,
                Map.of("platformUserId", u.getId(), "orgName", o.getName()), AuditService.PLATFORM_ORG_ID);
        state.logLogin(orgId, u.getId(), u.getPhone(), "ok", "platform-enter", "", "super-admin");
        return ApiResponse.ok(new AuthDtos.LoginResp(token, user, null, List.of(), false,
                Boolean.TRUE.equals(u.getMustChangePassword()), policy.expireInDays(u.getPasswordUpdatedAt())));
    }

    /** 超级管理员退出租户上下文，回到平台。 */
    @PostMapping("/exit-tenant")
    public ApiResponse exitTenant(HttpServletRequest request, HttpServletResponse response) {
        Long uid = CurrentUser.userIdOrNull();
        AppUser u = uid == null ? null : users.selectById(uid);
        if (u == null || !Boolean.TRUE.equals(u.getPlatformAdmin())) {
            throw BizException.forbidden("仅超级管理员可以退出租户上下文");
        }
        audit.record("tenant", CurrentUser.orgId(), "exit", null,
                Map.of("platformUserId", u.getId()), AuditService.PLATFORM_ORG_ID);
        return issuePlatform(u, request, response, "exit-tenant");
    }

    /**
     * 退出登录：清 Cookie <b>并且</b> 在服务端吊销本次会话（BUG-001）。
     * <p>
     * 历史实现只清 Cookie，令牌本身仍然有效——令牌一旦泄露，用户即便主动退出，
     * 攻击者仍可在剩余有效期内访问全部已授权数据。现在按 jti 落库吊销：
     * <ul>
     *   <li>立刻生效：{@code JwtAuthFilter} 下一次请求即 401 {@code SESSION_REVOKED}；</li>
     *   <li>重启不丢：吊销记录在 {@code revoked_token} 表里，不是内存 Map；</li>
     *   <li>只下线本设备：同一账号的其他会话（另一 jti）继续有效。</li>
     * </ul>
     * 令牌缺失/已失效时登出仍然返回 200（幂等），只是没有可吊销的会话。
     */
    @PostMapping("/logout")
    public ApiResponse logout(HttpServletRequest request, HttpServletResponse response) {
        String token = tokenOf(request);
        if (token != null) {
            try {
                Claims c = jwt.parse(token);
                String jti = c.getId();
                if (jti != null && !jti.isBlank()) {
                    Long uid = asLong(c.get("uid"));
                    Long orgId = asLong(c.get("org"));
                    Object rawPhone = c.get("phone");
                    String phone = rawPhone != null && !String.valueOf(rawPhone).isBlank()
                            ? String.valueOf(rawPhone) : c.getSubject();
                    Instant expiresAt = c.getExpiration() == null ? Instant.now() : c.getExpiration().toInstant();
                    boolean first = revocation.revoke(jti, uid, orgId, phone, "logout", expiresAt);
                    if (first && uid != null) {
                        // 会话流水：与登录事件同表，便于"谁在何时退出"的追溯（不影响"最近登录"口径）
                        state.logLogin(orgId, uid, phone, "logout", "logout",
                                clientIp(request), request.getHeader("User-Agent"));
                        audit.record("session", uid, "logout", null,
                                Map.of("jti", jti, "phone", phone == null ? "" : phone),
                                orgId == null ? AuditService.PLATFORM_ORG_ID : orgId,
                                phone == null ? "unknown" : phone, uid);
                    }
                    revocation.purgeExpiredIfDue();
                }
            } catch (Exception e) {
                // 令牌被篡改/已过期：登出是幂等动作，照常清 Cookie，不向调用方报错
                log.debug("登出时令牌无法解析，仅清理 Cookie：{}", e.getMessage());
            }
        }
        clearAuthCookies(response);
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    @PostMapping("/phone/rebind")
    public ApiResponse rebind(@RequestBody Map<String, String> body) {
        Long uid = CurrentUser.userIdOrNull();
        AppUser u = uid == null ? null : users.selectById(uid);
        if (u == null) throw new BizException(ErrorCode.AUTH_TOKEN_INVALID, "会话失效，请重新登录");
        String oldCode = body.get("oldCode");
        String newPhone = body.get("newPhone");
        String newCode = body.get("newCode");
        if (newPhone == null || !newPhone.matches("^1\\d{10}$")) throw BizException.validation("新手机号格式不正确");
        sms.verify(u.getPhone(), "rebind-old", oldCode);
        if (users.selectCount(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, newPhone).ne(AppUser::getId, u.getId())) > 0) {
            throw new BizException(ErrorCode.PHONE_TAKEN, "该手机号已被其他账号使用");
        }
        sms.verify(newPhone, "rebind-new", newCode);
        PhoneHistory h = new PhoneHistory();
        h.setUserId(u.getId());
        h.setOldPhone(u.getPhone());
        h.setNewPhone(newPhone);
        h.setChangedBy(CurrentUser.actor());
        h.setChangedAt(LocalDateTime.now());
        phoneHistory.insert(h);
        users.update(null, Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getId, u.getId())
                .set(AppUser::getPhone, newPhone)
                .setSql("token_version = token_version + 1"));
        state.invalidate(u.getId());
        return ApiResponse.ok(Map.of("rebound", true, "phone", Hashes.maskPhone(newPhone)));
    }

    /* ==================== 内部方法 ==================== */

    private AppUser findByAccount(String account) {
        AppUser u = users.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPhone, account).last("LIMIT 1"));
        if (u != null) return u;
        // username 兼容一版，供既有脚本与回滚使用
        return users.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getUsername, account).last("LIMIT 1"));
    }

    private String checkAccount(AppUser u, String rawPassword, String ip) {
        if (u == null || u.getDeletedAt() != null) return "bad_password";
        String blocked = accountBlocked(u);
        if (blocked != null) return blocked;
        if (u.getPasswordHash() == null) return "no_password";
        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(LocalDateTime.now())) return "locked";
        if (!encoder.matches(rawPassword, u.getPasswordHash())) {
            int failed = (u.getFailedCount() == null ? 0 : u.getFailedCount()) + 1;
            LocalDateTime lockUntil = failed >= 5 ? LocalDateTime.now().plusMinutes(15) : null;
            users.update(null, Wrappers.<AppUser>lambdaUpdate()
                    .eq(AppUser::getId, u.getId())
                    .set(AppUser::getFailedCount, failed)
                    .set(AppUser::getLockedUntil, lockUntil));
            return "bad_password";
        }
        return null;
    }

    private String accountBlocked(AppUser u) {
        if (!Boolean.TRUE.equals(u.getEnabled())) return "disabled";
        if ("disabled".equals(u.getStatus())) return "disabled";
        if ("locked".equals(u.getStatus())) return "locked";
        if ("pending".equals(u.getStatus())
                && u.getTempPasswordExpiresAt() != null
                && u.getTempPasswordExpiresAt().isBefore(LocalDateTime.now())) return "temp_expired";
        return null;
    }

    private BizException loginError(String fail, AppUser u) {
        return switch (fail) {
            case "disabled" -> new BizException(ErrorCode.ACCOUNT_DISABLED, "账号已停用，请联系管理员");
            case "locked" -> new BizException(ErrorCode.ACCOUNT_LOCKED, "尝试次数过多，请 15 分钟后再试");
            case "temp_expired" -> new BizException(ErrorCode.PASSWORD_TEMP_EXPIRED, "临时密码已过期，请联系管理员重置");
            case "no_password" -> BizException.validation("该账号未设置密码，请使用短信验证码登录");
            default -> new BizException(ErrorCode.AUTH_BAD_CREDENTIAL, "手机号或密码错误");
        };
    }

    private ApiResponse finishLogin(AppUser u, Long orgId, String channel, String ip,
                                    HttpServletRequest request, HttpServletResponse response) {
        users.update(null, Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getId, u.getId())
                .set(AppUser::getFailedCount, 0)
                .set(AppUser::getLockedUntil, null));
        if (Boolean.TRUE.equals(u.getPlatformAdmin())) {
            return issuePlatform(u, request, response, "password");
        }
        List<UserTenant> list = activeMembers(u.getId());
        if (list.isEmpty()) {
            state.logLogin(null, u.getId(), u.getPhone(), "no_tenant", channel, ip, request.getHeader("User-Agent"));
            // 被管理员停用（membership=disabled）要说清楚，不能和“从未加入企业”混为一谈
            Long stopped = members.selectCount(Wrappers.<UserTenant>lambdaQuery()
                    .eq(UserTenant::getUserId, u.getId()).eq(UserTenant::getStatus, "disabled"));
            if (stopped != null && stopped > 0) {
                throw new BizException(ErrorCode.MEMBER_DISABLED, "账号已被停用，请联系管理员");
            }
            throw BizException.forbidden("你还未被加入任何企业，请联系管理员");
        }
        UserTenant target = null;
        if (orgId != null) {
            target = list.stream().filter(t -> t.getOrgId().equals(orgId)).findFirst()
                    .orElseThrow(() -> BizException.notFound("你不在该企业中"));
        } else if (list.size() == 1) {
            target = list.get(0);
        } else if (u.getLastOrgId() != null) {
            target = list.stream().filter(t -> t.getOrgId().equals(u.getLastOrgId())).findFirst().orElse(null);
        }
        if (target == null && list.size() == 1) {
            assertTenantUsable(list.get(0).getOrgId());
        }
        if (target == null) {
            state.logLogin(null, u.getId(), u.getPhone(), "need_tenant", channel, ip, request.getHeader("User-Agent"));
            return ApiResponse.ok(new AuthDtos.LoginResp(null, null, jwt.issueTemp(u),
                    tenantOptions(list), true, Boolean.TRUE.equals(u.getMustChangePassword()),
                    policy.expireInDays(u.getPasswordUpdatedAt())));
        }
        assertTenantUsable(target.getOrgId());
        state.logLogin(target.getOrgId(), u.getId(), u.getPhone(), "ok", channel, ip, request.getHeader("User-Agent"));
        return issue(u, target, request, response);
    }

    /** 平台账号：不带租户（orgId=0），角色固定 platform_admin。 */
    private ApiResponse issuePlatform(AppUser u, HttpServletRequest request, HttpServletResponse response, String channel) {
        String token = jwt.issue(u, 0L, "platform_admin", "-", false);
        if (cookieMode) setAuthCookies(response, token);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", u.getId());
        user.put("username", u.getUsername() == null ? u.getPhone() : u.getUsername());
        user.put("phone", Hashes.maskPhone(u.getPhone()));
        user.put("phoneRaw", u.getPhone());
        user.put("displayName", u.getDisplayName());
        user.put("role", "platform_admin");
        user.put("orgId", 0);
        user.put("orgName", "平台管理");
        user.put("userType", "tenant_admin");
        user.put("pages", List.of());
        user.put("mustChangePassword", Boolean.TRUE.equals(u.getMustChangePassword()));
        state.logLogin(null, u.getId(), u.getPhone(), "ok", channel, "", "");
        return ApiResponse.ok(new AuthDtos.LoginResp(token, user, null, List.of(), false,
                Boolean.TRUE.equals(u.getMustChangePassword()),
                policy.expireInDays(u.getPasswordUpdatedAt())));
    }

    private ApiResponse issue(AppUser u, UserTenant t, HttpServletRequest request, HttpServletResponse response) {
        users.update(null, Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getId, u.getId()).set(AppUser::getLastOrgId, t.getOrgId()));
        boolean tenantAdmin = "admin".equals(t.getRole()) || "tenant_admin".equals(t.getUserType());
        List<String> pages = tenantAdmin ? RoleService.allPages() : roleService.pagesOf(t.getRoleId());
        String token = jwt.issue(u, t.getOrgId(), tenantAdmin ? "admin" : "user",
                tenantAdmin ? "*" : String.join(",", pages), false);
        if (cookieMode) setAuthCookies(response, token);
        AuthStateService.MemberState m = state.member(t.getOrgId(), u.getId());
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", u.getId());
        user.put("username", u.getUsername() == null ? u.getPhone() : u.getUsername());
        user.put("phone", Hashes.maskPhone(u.getPhone()));
        user.put("phoneRaw", u.getPhone());
        user.put("displayName", u.getDisplayName());
        user.put("role", t.getRole());
        user.put("orgId", t.getOrgId());
        user.put("orgName", m == null ? "" : m.orgName());
        user.put("userType", tenantAdmin ? "tenant_admin" : "user");
        user.put("roleId", t.getRoleId());
        user.put("roleName", tenantAdmin ? "租户管理员" : "");
        user.put("pages", tenantAdmin ? List.of("*") : pages);
        user.put("mustChangePassword", Boolean.TRUE.equals(u.getMustChangePassword()));
        putLicense(user, t.getOrgId());
        return ApiResponse.ok(new AuthDtos.LoginResp(token, user, null, tenantsOf(u.getId()),
                false, Boolean.TRUE.equals(u.getMustChangePassword()), policy.expireInDays(u.getPasswordUpdatedAt())));
    }

    /** 授权期信息：登录、/me、代管进入三处都要带，前端据此渲染到期横幅。 */
    private void putLicense(Map<String, Object> user, Long orgId) {
        Org org = orgId == null ? null : orgs.selectById(orgId);
        if (org == null) return;
        LocalDate end = org.getExpireAt() == null ? null : org.getExpireAt().toLocalDate();
        user.put("licenseEndAt", end == null ? null : end.toString());
        user.put("licenseRemainDays", end == null ? null
                : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), end));
        user.put("licenseStatus", com.carbonbal.platform.PlatformService
                .effectiveStatus(org.getStatus(), org.getExpireAt()));
        user.put("plan", org.getPlan());
        user.put("seatLimit", org.getSeatLimit());
    }

    /** 租户授权是否可用：未生效、停用、到期一律禁止登录。 */
    private void assertTenantUsable(Long orgId) {
        Org o = orgs.selectById(orgId);
        if (o == null) throw new BizException(ErrorCode.TENANT_NOT_FOUND, "企业不存在");
        String st = o.getStatus() == null ? "active" : o.getStatus();
        if ("disabled".equals(st)) st = "suspended";
        if ("pending".equals(st)) throw new BizException(ErrorCode.TENANT_PENDING, "企业授权尚未生效");
        if ("suspended".equals(st)) throw new BizException(ErrorCode.TENANT_SUSPENDED, "企业已停用，请联系平台管理员");
        if ("expired".equals(st)) throw new BizException(ErrorCode.TENANT_EXPIRED, "企业授权已到期，请联系平台管理员续期");
        if (o.getLicenseStartAt() != null && o.getLicenseStartAt().isAfter(LocalDate.now())) {
            throw new BizException(ErrorCode.TENANT_PENDING, "企业授权尚未生效");
        }
        if (o.getExpireAt() != null && o.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.TENANT_EXPIRED, "企业授权已到期，请联系平台管理员续期");
        }
    }

    private String orgNameOf(Long orgId) {
        Org o = orgId == null ? null : orgs.selectById(orgId);
        return o == null ? "" : o.getName();
    }

    private UserTenant activeMember(Long orgId, Long userId) {
        UserTenant t = members.selectOne(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getOrgId, orgId).eq(UserTenant::getUserId, userId).last("LIMIT 1"));
        if (t == null || !"active".equals(t.getStatus())) return null;
        return t;
    }

    private List<UserTenant> activeMembers(Long userId) {
        List<UserTenant> list = members.selectList(Wrappers.<UserTenant>lambdaQuery()
                .eq(UserTenant::getUserId, userId).eq(UserTenant::getStatus, "active")
                .orderByAsc(UserTenant::getOrgId));
        return list;
    }

    private List<Map<String, Object>> tenantsOf(Long userId) {
        return tenantOptions(activeMembers(userId));
    }

    private List<Map<String, Object>> tenantOptions(List<UserTenant> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserTenant t : list) {
            AuthStateService.MemberState m = state.member(t.getOrgId(), t.getUserId());
            Map<String, Object> o = new LinkedHashMap<>();
            Org org = orgs.selectById(t.getOrgId());
            o.put("orgId", t.getOrgId());
            o.put("orgName", m == null ? "" : m.orgName());
            o.put("role", t.getRole());
            o.put("status", org == null ? "active" : (org.getStatus() == null ? "active" : org.getStatus()));
            o.put("expireAt", org == null || org.getExpireAt() == null ? null
                    : org.getExpireAt().toLocalDate().toString());
            out.add(o);
        }
        return out;
    }

    private List<String> permissions(String role) {
        if (role == null) return List.of();
        return switch (role) {
            case "admin" -> List.of("user:manage", "org:edit", "data:write", "data:read",
                    "accounting:review", "accounting:approve", "report:delete", "export:run");
            case "filler" -> List.of("data:write", "data:read", "accounting:submit", "export:run");
            case "reviewer" -> List.of("data:read", "accounting:review");
            case "approver" -> List.of("data:read", "accounting:approve", "accounting:lock");
            case "platform_admin" -> List.of("platform:manage");
            default -> List.of("data:read");
        };
    }

    /** 改密：校验策略与历史，落库并让旧令牌失效。 */
    void applyNewPassword(AppUser u, String raw) {
        policy.validate(raw);
        String hash = encoder.encode(raw);
        if (policy.historySize() > 0 && u.getId() != null) {
            List<PasswordHistory> recent = passwordHistory.selectList(
                    Wrappers.<PasswordHistory>lambdaQuery()
                            .eq(PasswordHistory::getUserId, u.getId())
                            .orderByDesc(PasswordHistory::getId)
                            .last("LIMIT " + policy.historySize()));
            for (PasswordHistory h : recent) {
                if (encoder.matches(raw, h.getPasswordHash())) {
                    throw BizException.validation("新密码不能与最近 " + policy.historySize() + " 次使用过的密码相同");
                }
            }
        }
        users.update(null, Wrappers.<AppUser>lambdaUpdate()
                .eq(AppUser::getId, u.getId())
                .set(AppUser::getPasswordHash, hash)
                .set(AppUser::getPasswordUpdatedAt, LocalDateTime.now())
                .set(AppUser::getMustChangePassword, false)
                .set(AppUser::getTempPasswordExpiresAt, null)
                .set(AppUser::getStatus, "active")
                .setSql("token_version = token_version + 1"));
        PasswordHistory h = new PasswordHistory();
        h.setUserId(u.getId());
        h.setPasswordHash(hash);
        h.setCreatedAt(LocalDateTime.now());
        passwordHistory.insert(h);
        state.invalidate(u.getId());
    }

    private void setAuthCookies(HttpServletResponse res, String token) {
        String csrf = java.util.UUID.randomUUID().toString().replace("-", "");
        jakarta.servlet.http.Cookie t = new jakarta.servlet.http.Cookie("cb_token", token);
        t.setHttpOnly(true);
        t.setSecure(cookieSecure);
        t.setPath("/");
        t.setMaxAge(ttlHours * 3600);
        t.setAttribute("SameSite", "Lax");
        res.addCookie(t);
        jakarta.servlet.http.Cookie c = new jakarta.servlet.http.Cookie("cb_csrf", csrf);
        c.setSecure(cookieSecure);
        c.setPath("/");
        c.setMaxAge(ttlHours * 3600);
        c.setAttribute("SameSite", "Lax");
        res.addCookie(c);
    }

    private void clearAuthCookies(HttpServletResponse res) {
        for (String name : List.of("cb_token", "cb_csrf")) {
            jakarta.servlet.http.Cookie c = new jakarta.servlet.http.Cookie(name, "");
            c.setSecure(cookieSecure);
            c.setPath("/");
            c.setMaxAge(0);
            res.addCookie(c);
        }
        res.setHeader("Clear-Site-Data", "\"cookies\"");
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v != null) {
            try { return Long.parseLong(v.toString()); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    /**
     * 取当前请求携带的令牌：优先 HttpOnly Cookie（浏览器），回退 {@code Authorization: Bearer}（脚本/测试）。
     * <p>
     * 登出接口在过滤器里属于匿名接口（不解析令牌），因此这里必须自己取一次，
     * 才能拿到 jti 做服务端吊销。
     *
     * @param req 当前请求
     * @return 令牌原文；未携带返回 null
     */
    private static String tokenOf(HttpServletRequest req) {
        if (req == null) return null;
        jakarta.servlet.http.Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie ck : cookies) {
                if ("cb_token".equals(ck.getName()) && ck.getValue() != null && !ck.getValue().isBlank()) {
                    return ck.getValue();
                }
            }
        }
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String v = header.substring(7).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    /**
     * 解析客户端 IP（P1-3）。
     * <p>
     * 只有 {@code remoteAddr} 命中 {@code app.auth.trusted-proxies} 时才认为请求经过可信代理，
     * 此时从 {@code X-Forwarded-For} 的<b>最右</b>向左取第一个非可信地址（最右段由最近的代理写入，
     * 最左段可被客户端随意伪造）。白名单为空时一律使用 {@code remoteAddr}，
     * 从而关闭"轮换 XFF 绕过登录锁定"的攻击面。
     *
     * @param req 当前请求
     * @return 用于锁定计数与审计的客户端地址，可能为空串
     */
    private String clientIp(HttpServletRequest req) {
        String remote = req.getRemoteAddr() == null ? "" : req.getRemoteAddr().trim();
        if (trustedProxies.isEmpty() || !trustedProxies.contains(remote)) return remote;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) return remote;
        String[] hops = xff.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty() || trustedProxies.contains(hop)) continue;
            return hop;
        }
        return remote;
    }
}
