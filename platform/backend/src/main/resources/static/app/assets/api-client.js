/* 平台适配层：统一走后端真实接口。 */
(function () {
  const BASE = '/api';
  const TOKEN_KEY = 'cb_token';
  document.body.classList.add('platform');
  const toast = (...args) => (window.UI && typeof UI.toast === 'function' ? UI.toast(...args) : console.log('[toast]', ...args));
  let token = '';
  let me = null;
  let starting = false;
  let authResolve = null;
  let tempToken = null;
  let captchaId = null;
  let authTab = 'pwd';
  let capEnabled = false;
  let sendTimer = 0;

  function csrfToken() {
    const m = document.cookie.match(/(?:^|;\s*)cb_csrf=([^;]+)/);
    return m ? decodeURIComponent(m[1]) : '';
  }

  function waitLogin() {
    return new Promise(resolve => { authResolve = resolve; });
  }

  function writeHeaders() {
    const h = {};
    const c = csrfToken();
    if (c) h['X-CSRF-Token'] = c;
    return h;
  }

  // 同一请求体 5 秒内复用同一个幂等键：双击/连点只创建一条，网络重试也能命中首次结果
  const IDEM_TTL = 5000;
  const idemCache = new Map();
  function idemKey(method, path, body) {
    if (String(method).toUpperCase() !== 'POST') return null;
    if (body === undefined || body === null || body instanceof FormData) return null;
    if (path.startsWith('/auth/') || path.startsWith('/platform/')) return null;
    const sig = path + '\n' + JSON.stringify(body);
    const now = Date.now();
    const hit = idemCache.get(sig);
    if (hit && now - hit.at < IDEM_TTL) return hit.key;
    const key = (window.crypto && crypto.randomUUID)
      ? crypto.randomUUID()
      : 'k-' + now + '-' + Math.random().toString(36).slice(2, 10);
    idemCache.set(sig, { key, at: now });
    if (idemCache.size > 40) { idemCache.forEach((v, k) => { if (now - v.at > IDEM_TTL) idemCache.delete(k); }); }
    return key;
  }

  async function request(method, path, body) {
    const write = !['GET', 'HEAD'].includes(String(method).toUpperCase());
    const opt = { method, credentials: 'same-origin', headers: write ? writeHeaders() : {} };
    if (body instanceof FormData) opt.body = body;
    else if (body !== undefined && body !== null) {
      opt.headers['Content-Type'] = 'application/json';
      opt.body = JSON.stringify(body);
    }
    const idem = idemKey(method, path, body);
    if (idem) opt.headers['Idempotency-Key'] = idem;
    let res;
    try {
      res = await fetch(BASE + path, opt);
    } catch (e) {
      // 浏览器网络层错误对用户没有意义，统一中文提示；技术细节保留在控制台
      if (e && (e.name === 'TypeError' || /failed to fetch|networkerror|load failed/i.test(String(e.message || '')))) {
        console.warn('[api] 网络请求失败', method, path, e);
        throw new Error('网络异常，请检查网络连接后重试');
      }
      throw e;
    }
    if (res.status === 401 && !path.startsWith('/auth/')) {
      clearAuth();
      showLogin('登录已过期，请重新登录');
      throw new Error('登录已过期');
    }
    let json = null;
    try { json = await res.json(); } catch (e) { json = null; }
    if (!res.ok || !json || json.status !== 0) {
      const err = new Error((json && json.body && json.body.message) || ('请求失败 ' + res.status));
      err.code = json ? json.status : res.status;
      err.detail = json && json.body ? json.body.detail : null;
      throw err;
    }
    return json.body;
  }

  function clearAuth() {
    token = '';
    me = null;
    tempToken = null;
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem('cb_me');
  }

  async function afterLogin(r) {
    if (r && r.needTenantSelect) {
      tempToken = r.tempToken;
      showTenantPicker(r.tenants || []);
      return false;
    }
    token = r.token || '';
    me = Object.assign({}, r.user, { tenants: r.tenants || [], currentOrgId: r.user && r.user.orgId });
    localStorage.setItem('cb_me', JSON.stringify(r.user));
    if (me.mustChangePassword) {
      // 初始密码未改：先改密再进系统（后端对业务接口一律 403 兜底）
      hideLogin();
      requirePasswordChange();
      return true;
    }
    await load();
    hideLogin();
    return true;
  }

  /** 强制改密：弹窗不可真正关闭，改完用新密码重新登录。 */
  function requirePasswordChange() {
    const open = () => {
      if (window.UI && typeof UI.openPwd === 'function') {
        UI.openPwd({ forced: true, onDone: () => logout() });
      } else {
        setTimeout(open, 300);
      }
    };
    open();
  }

  async function passwordLogin(phone, password, capId, capCode) {
    const body = { phone, password };
    if (capId) { body.captchaId = capId; body.captchaCode = capCode || ''; }
    return afterLogin(await request('POST', '/auth/login', body));
  }

  async function smsLogin(phone, code) {
    return afterLogin(await request('POST', '/auth/sms/login', { phone, code }));
  }

  async function fetchCaptcha() {
    const r = await request('GET', '/auth/captcha');
    const field = document.querySelector('#auth-cap-field');
    if (!r || !r.enabled) {
      capEnabled = false;
      if (field) field.classList.add('hide');
      return null;
    }
    capEnabled = true;
    captchaId = r.captchaId;
    const img = document.querySelector('#auth-cap-img');
    if (img) img.src = r.image;
    const cap = document.querySelector('#auth-cap');
    if (cap) cap.value = '';
    if (field) field.classList.remove('hide');
    return r;
  }

  async function sendSmsCode(phone) {
    const cap = document.querySelector('#auth-cap');
    return request('POST', '/auth/sms/send', {
      phone, scene: 'login', captchaId, captchaCode: cap ? cap.value.trim() : ''
    });
  }

  async function chooseTenant(orgId) {
    const r = await request('POST', '/auth/tenant/select', { tempToken, orgId });
    return afterLogin(r);
  }

  function showTenantPicker(tenants) {
    const card = document.querySelector('#tenant-card');
    const list = document.querySelector('#tenant-list');
    if (!card || !list) return;
    document.querySelector('.auth-card').classList.add('hide');
    card.classList.remove('hide');
    list.innerHTML = tenants.map(t => `<div class="tenant-item" data-org="${t.orgId}">
      <b>${UI.esc(t.orgName || ('企业 ' + t.orgId))}</b>
      <span>${UI.esc(roleLabel(t.role))}</span></div>`).join('');
    list.querySelectorAll('[data-org]').forEach(el => el.onclick = async () => {
      try {
        await chooseTenant(Number(el.dataset.org));
      } catch (e) {
        document.querySelector('#auth-err').textContent = e.message;
      }
    });
  }

  const roleOf = () => (me && me.role) || '';
  const isAdmin = () => roleOf() === 'admin';
  const canWrite = () => roleOf() === 'admin' || roleOf() === 'user';
  // 与后端 @PreAuthorize 保持一致：ADMIN/USER 均可进入复核/批准，职责分离由服务端按人校验
  const canReview = () => roleOf() === 'admin' || roleOf() === 'user' || roleOf() === 'reviewer';
  const canApprove = () => roleOf() === 'admin' || roleOf() === 'user' || roleOf() === 'approver';

  function roleLabel(role) {
    return ({ admin: '租户管理员', filler: '填报员', reviewer: '复核人', approver: '批准人',
      viewer: '查看者', platform_admin: '超级管理员' })[role] || role;
  }

  const emptyDb = () => ({
    org: {}, kpi: {}, monthly: [], todos: [], energyMix: [],
    quota: { history: [], total: 0, used: 0, next: 0, year: new Date().getFullYear() },
    accounting: [], accountingLines: [], products: [], prodValue: [], totalValue: [],
    suppliers: [], footprint: [], assets: [], ccers: [], trades: [], evidences: [],
    thirdReports: [], facilities: [], devices: [], deviceData: [],
    alertRules: [], alertRecords: [], warningEvents: [], reports: [], retrofits: [], energyMgmt: []
  });

  async function load() {
    if (!me) {
      const r = await request('GET', '/auth/me');
      me = Object.assign({}, r.user, {
        tenants: r.tenants || [], currentOrgId: r.currentOrgId,
        role: r.role || (r.user && r.user.role), permissions: r.permissions || [],
        passwordExpireInDays: r.passwordExpireInDays
      });
    }
    const year = new Date().getFullYear();
    // 平台账号没有业务快照：登录后看平台端
    const noSnapshot = me && me.role === 'platform_admin' && !me.impersonating;
    const snap = noSnapshot ? {} : await request('GET', '/bootstrap?year=' + year);
    Object.keys(window.DB || {}).forEach(k => { delete window.DB[k]; });
    // 快照会按页面权限裁剪，这里先补空结构，避免页面在缺失键上取值崩溃
    Object.assign(window.DB, emptyDb(), snap);
    try {
      paintUser();
      applyRole();
      schedulePasswordPrompt();
      warnPasswordExpiry();
    } catch (e) {
      window.__uiError = e.message + ' | ' + (e.stack || '');
      console.error('UI 初始化失败', e);
    }
  }

  async function reloadQuiet() {
    try { await load(); UI.render(); } catch (e) { console.error('刷新失败', e); }
  }

  async function refresh() {
    try {
      await load();
      UI.render();
      toast('ok', '数据已刷新');
    } catch (e) {
      toast('err', e.message);
    }
  }

  /** 密码 15 天内到期时提示一次，不阻塞操作。 */
  function warnPasswordExpiry() {
    if (!me || me.mustChangePassword) return;
    const days = me.passwordExpireInDays;
    if (days == null || days > 15) return;
    const key = 'cb_pwd_warn_' + (me.userId || '') + '_' + new Date().toISOString().slice(0, 10);
    if (sessionStorage.getItem(key)) return;
    sessionStorage.setItem(key, '1');
    setTimeout(() => {
      toast('warn', days <= 0 ? '密码已过期，请尽快修改' : ('密码将在 ' + days + ' 天后过期'), '用户菜单 → 修改密码');
    }, 1200);
  }

  /** 初始密码未改时弹窗拦截；正常账号不打扰。 */
  function schedulePasswordPrompt() {
    if (!me || !me.mustChangePassword) return;
    setTimeout(() => {
      if (window.UI && typeof UI.openPwd === 'function') UI.openPwd();
    }, 800);
  }

  /** 换绑手机号：旧号验证码 + 新号验证码。 */
  function rebindPhoneDialog() {
    const raw = (me && me.phoneRaw) || '';
    UI.modal({
      title: '换绑手机号', width: 520,
      body: `<div class="stack">
        <div class="field"><label>当前手机号</label><input class="input" value="${UI.esc(me && me.phone || '')}" readonly></div>
        <div class="field"><label class="req">图形验证码</label>
          <div class="row" style="gap:8px"><input class="input" id="rb-cap" maxlength="4" style="flex:1" placeholder="右侧字符">
            <img id="rb-cap-img" alt="图形验证码" style="height:34px;width:100px;border:1px solid var(--line-strong);border-radius:8px;cursor:pointer"></div></div>
        <div class="field"><label class="req">原号验证码</label>
          <div class="row" style="gap:8px"><input class="input" id="rb-old" style="flex:1" maxlength="6" placeholder="发送到当前手机号">
            <button class="btn" id="rb-send-old" type="button">发送</button></div></div>
        <div class="hr" style="margin:6px 0"></div>
        <div class="field"><label class="req">新手机号</label><input class="input" id="rb-phone" maxlength="11" placeholder="11 位手机号"></div>
        <div class="field"><label class="req">新号验证码</label>
          <div class="row" style="gap:8px"><input class="input" id="rb-new" style="flex:1" maxlength="6" placeholder="发送到新手机号">
            <button class="btn" id="rb-send-new" type="button">发送</button></div></div>
        <div class="small muted">换绑后所有已登录设备需要重新登录。</div>
      </div>`,
      foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="rb-ok">确认换绑</button>`,
      onMount(wrap, close) {
        let capId = null;
        const refreshCap = async () => {
          try {
            const cap = await request('GET', '/auth/captcha');
            if (cap && cap.enabled) {
              capId = cap.captchaId;
              wrap.querySelector('#rb-cap-img').src = cap.image;
            }
          } catch (e) { /* 图形码未启用时忽略 */ }
        };
        refreshCap();
        wrap.querySelector('#rb-cap-img').onclick = refreshCap;
        const send = async (phone, scene, btn) => {
          if (!/^1\d{10}$/.test(phone)) { toast('err', '手机号格式不正确'); return; }
          const code = wrap.querySelector('#rb-cap').value.trim();
          if (!code) { toast('err', '请先填写图形验证码'); return; }
          btn.disabled = true;
          try {
            const r = await request('POST', '/auth/sms/send',
              { phone, scene, captchaId: capId, captchaCode: code });
            toast('ok', '验证码已发送');
            wrap.querySelector('#rb-cap').value = '';
            refreshCap();
          } catch (e) {
            toast('err', e.message);
            refreshCap();
          } finally { setTimeout(() => { btn.disabled = false; }, 3000); }
        };
        wrap.querySelector('#rb-send-old').onclick = (e) => send(raw, 'rebind-old', e.currentTarget);
        wrap.querySelector('#rb-send-new').onclick = (e) =>
          send(wrap.querySelector('#rb-phone').value.trim(), 'rebind-new', e.currentTarget);
        wrap.querySelector('#rb-ok').onclick = async (e) => {
          const body = {
            oldCode: wrap.querySelector('#rb-old').value.trim(),
            newPhone: wrap.querySelector('#rb-phone').value.trim(),
            newCode: wrap.querySelector('#rb-new').value.trim()
          };
          if (!body.oldCode || !body.newCode) { toast('err', '请填写两个验证码'); return; }
          const done = busy(e.currentTarget, '提交中');
          try {
            await request('POST', '/auth/phone/rebind', body);
            done(); close();
            toast('ok', '手机号已换绑', '请用新手机号重新登录');
            setTimeout(() => logout(), 1000);
          } catch (err) { done(); toast('err', err.message); }
        };
      }
    });
  }

  async function logout() {
    // 退出以服务端清 Cookie 为准；请求失败也先清本地，避免界面停在已登录状态
    try { await request('POST', '/auth/logout', {}); } catch (e) { }
    clearAuth();
    sessionStorage.removeItem('cb_pwd_prompt');
    const menu = document.querySelector('#user-menu');
    if (menu) menu.classList.remove('open');
    // replace：不把“已登录页面”留在历史里，后退不会回到登录态
    location.replace('/app/');
  }

  function switchTenantDialog() {
    const list = (me && me.tenants) || [];
    if (list.length <= 1) {
      toast('warn', '你只属于一家企业', '如需访问其他企业，请联系管理员');
      return;
    }
    UI.modal({
      title: '切换企业', width: 460,
      body: `<div class="small muted" style="margin-bottom:10px">切换后将重新加载该企业的数据</div>
        <div class="stack">${list.map(t => `<div class="between" data-org="${t.orgId}"
          style="padding:11px 13px;border:1px solid ${t.orgId === me.currentOrgId ? '#12A374' : '#E2E8E5'};border-radius:10px;cursor:pointer">
          <div><b>${UI.esc(t.orgName || ('企业 ' + t.orgId))}</b>
            <div class="small muted">${UI.esc(roleLabel(t.role))}</div></div>
          ${t.orgId === me.currentOrgId ? '<span class="tag tag-ok dot">当前</span>' : ''}
        </div>`).join('')}</div>`,
      foot: `<button class="btn" data-close>取消</button>`,
      onMount(wrap, close) {
        wrap.querySelectorAll('[data-org]').forEach(el => el.onclick = async () => {
          const orgId = Number(el.dataset.org);
          if (orgId === me.currentOrgId) { close(); return; }
          try {
            await request('POST', '/auth/switch-tenant', { orgId });
            Object.keys(window.DB || {}).forEach(k => { delete window.DB[k]; });
            me = null;
            await load();
            close();
            location.hash = '#/home';
            UI.render();
            toast('ok', '已切换企业', (me && me.orgName) || '');
          } catch (e) {
            toast('err', e.message);
          }
        });
      }
    });
  }

  function paintUser() {
    const org = (window.DB && DB.org) || {};
    const name = (me && (me.displayName || me.username)) || '用户';
    const impersonating = !!(me && me.impersonating);
    const set = (sel, v) => { const el = document.querySelector(sel); if (el) el.textContent = v; };
    set('#side-org', org.name || (me && me.orgName) || '');
    set('#side-mode', impersonating ? '平台代管'
      : (me && me.role === 'platform_admin' ? '平台管理' : '实时数据'));
    set('#avatar', name.slice(0, 1));
    const head = document.querySelector('#user-menu .um-head');
    if (head) head.innerHTML = `<b>${UI.esc(name)}</b><div class="small muted">${UI.esc(org.name || (me && me.orgName) || '')}</div>`;
    paintContextBanner();
    paintLicenseBanner();
    // 平台账号没有业务页面，登录/刷新后直接落到平台概览
    if (me && me.role === 'platform_admin' && !me.impersonating) {
      const cur = (location.hash || '').replace(/^#/, '');
      if (!cur || cur === '/' || cur === '/home') location.hash = '#/platform/overview';
    }
  }

  /** 租户授权到期提醒：30 天内提示，7 天内标红；只做站内，不发短信。 */
  function paintLicenseBanner() {
    const view = document.querySelector('#view');
    if (!view || !view.parentNode) return;
    let el = document.querySelector('#license-banner');
    if (!el) {
      el = document.createElement('div');
      el.id = 'license-banner';
      view.parentNode.insertBefore(el, view);
    }
    const days = me && me.licenseRemainDays;
    const show = me && me.role !== 'platform_admin' && me.licenseStatus !== 'suspended'
      && typeof days === 'number' && days <= 30;
    if (!show) { el.style.display = 'none'; return; }
    const danger = days <= 7;
    el.className = 'ctx-banner' + (danger ? ' lic-danger' : '');
    el.style.display = '';
    const when = me.licenseEndAt ? '（' + me.licenseEndAt + '）' : '';
    const text = days < 0
      ? `授权已于 ${-days} 天前到期${when}，业务数据已锁定`
      : days === 0
        ? `授权今天到期${when}，到期后本企业全部账号将无法登录`
        : `授权将于 ${days} 天后到期${when}，请联系平台续期`;
    el.innerHTML = `<span class="ctx-dot"></span><span>${UI.esc(text)}</span>
      <span class="spacer" style="flex:1"></span>
      ${me.role === 'admin' ? `<button class="btn btn-sm" id="lic-view">查看授权</button>` : ''}`;
    const btn = el.querySelector('#lic-view');
    if (btn) btn.onclick = () => { location.hash = '#/system/tenant'; };
  }

  /** 超级管理员进入租户时的上下文横幅：可随时退出租户。 */
  function paintContextBanner() {
    let el = document.querySelector('#ctx-banner');
    if (!el) {
      const view = document.querySelector('#view');
      if (!view || !view.parentNode) return;
      el = document.createElement('div');
      el.id = 'ctx-banner';
      el.className = 'ctx-banner';
      view.parentNode.insertBefore(el, view);
    }
    if (!me || !me.impersonating) { el.style.display = 'none'; return; }
    el.style.display = '';
    el.innerHTML = `<span class="ctx-dot"></span>
      <span><b>超级管理员</b> 正在以「${UI.esc(me.orgName || '')}」租户上下文操作</span>
      <span class="small muted">不受角色与职责分离限制，操作留痕</span>
      <span class="spacer" style="flex:1"></span>
      <button class="btn btn-sm" id="ctx-exit">退出租户</button>`;
    el.querySelector('#ctx-exit').onclick = async () => {
      try {
        await request('POST', '/auth/exit-tenant', {});
        location.href = '/app/';
      } catch (e) { UI.toast('err', e.message); }
    };
  }

  function applyRole() {
    if (!window.UI) return;
    UI.state.role = me && me.role === 'admin' ? 'admin' : 'operator';
    UI.state.roleName = me ? roleLabel(me.role) : '';
    UI.state.pages = (me && me.pages) || ['*'];
    UI.state.platformAdmin = !!(me && me.role === 'platform_admin');
    document.body.classList.toggle('role-viewer', !!(me && me.role === 'viewer'));
    if (!UI.__platformPatched) {
      const orig = UI.render;
      UI.render = async function () {
        await orig.apply(UI, arguments);
        lockWrites();
        lockReadOnly();
      };
      UI.__platformPatched = true;
    }
    if (!window.__platformObserver) {
      const view = document.querySelector('#view');
      if (view) {
        new MutationObserver(() => { lockWrites(); lockReadOnly(); })
          .observe(view, { childList: true, subtree: true });
        window.__platformObserver = true;
      }
    }
    lockWrites();
    lockReadOnly();
  }

  /**
   * 页面内动作不再按角色分级：能进入页面即可新增/编辑/流转。
   * 保留一条护栏——删除仍限租户管理员（后端同样是 ADMIN）。
   */
  function lockWrites() {
    if (!window.UI || !UI.state || !me) return;
    const view = document.querySelector('#view');
    if (!view || me.role === 'admin') return;
    view.querySelectorAll('button').forEach(btn => {
      const text = (btn.textContent || '').trim();
      if (/^删除$/.test(text) || /^批量删除$/.test(text) || text === '移出') btn.remove();
    });
    view.querySelectorAll('[data-del], [data-del-rpt], #g-bulk, #q-bulk, #fp-bulk, #rec-bulk, #w-bulk, #d-bulk')
      .forEach(b => b.remove());
  }

  function lockReadOnly() { /* 三级身份后不再有只读角色，页面权限由后端与路由守卫控制 */ }

  /* ---------- 登录壳 ---------- */
  function showLogin(msg) {
    const card = document.querySelector('#tenant-card');
    const authCard = document.querySelector('.auth-card');
    if (card) card.classList.add('hide');
    if (authCard) authCard.classList.remove('hide');
    const mask = document.querySelector('#auth-mask');
    if (!mask) return;
    document.body.classList.remove('authed');
    mask.classList.remove('hide');
    document.querySelector('#auth-err').textContent = msg || '';
    // 退出或会话过期后回到登录页，旧验证码多半已用过/过期，换一张
    fetchCaptcha().catch(() => {});
  }

  function hideLogin() {
    document.body.classList.add('authed');
    const mask = document.querySelector('#auth-mask');
    if (mask) mask.classList.add('hide');
  }

  function switchAuthTab(tab) {
    authTab = tab;
    document.querySelectorAll('[data-auth-tab]').forEach(b => b.classList.toggle('on', b.dataset.authTab === tab));
    const pwd = document.querySelector('#auth-pwd-field');
    const sms = document.querySelector('#auth-sms-field');
    const cap = document.querySelector('#auth-cap-field');
    if (pwd) pwd.classList.toggle('hide', tab !== 'pwd');
    if (sms) sms.classList.toggle('hide', tab !== 'sms');
    // 密码与短信两种登录都要图形验证码
    if (cap) cap.classList.toggle('hide', !capEnabled);
    if (!document.querySelector('#auth-cap-img').src) {
      fetchCaptcha().catch(() => {});
    }
    const err = document.querySelector('#auth-err');
    if (err) err.textContent = '';
  }

  function authError(msg) {
    const el = document.querySelector('#auth-err');
    if (el) el.textContent = msg || '';
  }

  function startCountdown() {
    const btn = document.querySelector('#auth-send');
    if (!btn) return;
    let left = 60;
    btn.disabled = true;
    btn.textContent = left + ' 秒后重发';
    sendTimer = setInterval(() => {
      left -= 1;
      if (left <= 0) {
        clearInterval(sendTimer);
        btn.disabled = false;
        btn.textContent = '获取验证码';
      } else {
        btn.textContent = left + ' 秒后重发';
      }
    }, 1000);
  }

  function bindLogin() {
    const btn = document.querySelector('#auth-login');
    if (!btn || btn.dataset.bound) return;
    btn.dataset.bound = '1';

    document.querySelectorAll('[data-auth-tab]').forEach(t => {
      t.onclick = () => switchAuthTab(t.dataset.authTab);
    });
    const capImg = document.querySelector('#auth-cap-img');
    if (capImg) capImg.onclick = () => fetchCaptcha().catch(e => authError(e.message));
    // 进入登录页就取一张验证码，密码页签同样需要
    fetchCaptcha().catch(() => {});
    const send = document.querySelector('#auth-send');
    if (send) {
      send.onclick = async () => {
        const phone = (document.querySelector('#auth-phone').value || '').trim();
        const cap = document.querySelector('#auth-cap');
        if (!/^1\d{10}$/.test(phone)) { authError('请输入 11 位手机号'); return; }
        if (!cap || !cap.value.trim()) { authError('请先填写图形验证码'); return; }
        send.disabled = true;
        try {
          await sendSmsCode(phone);
          startCountdown();
          authError('');
          toast('ok', '验证码已发送');
        } catch (e) {
          send.disabled = false;
          authError(e.message);
          fetchCaptcha().catch(() => {});
        }
      };
    }

    const submit = async () => {
      const phone = (document.querySelector('#auth-phone').value || '').trim();
      authError('');
      if (!/^1\d{10}$/.test(phone)) { authError('请输入 11 位手机号'); return; }
      btn.disabled = true;
      const label = btn.textContent;
      btn.textContent = '登录中…';
      try {
        let ok;
        if (authTab === 'sms') {
          const code = (document.querySelector('#auth-code').value || '').trim();
          if (!code) throw new Error('请输入短信验证码');
          ok = await smsLogin(phone, code);
        } else {
          const pwd = document.querySelector('#auth-pwd').value;
          if (!pwd) throw new Error('请输入密码');
          let capCode = '';
          if (capEnabled) {
            capCode = (document.querySelector('#auth-cap').value || '').trim();
            if (!capCode) throw new Error('请填写图形验证码');
            if (!captchaId) await fetchCaptcha();
          }
          ok = await passwordLogin(phone, pwd, captchaId, capCode);
        }
        if (ok && authResolve) { const r = authResolve; authResolve = null; r(true); }
      } catch (e) {
        authError(e.message);
        // 验证码一次性，失败后必须换一张，否则重试必然再次失败
        if (/验证码/.test(e.message || '')) fetchCaptcha().catch(() => {});
      } finally {
        btn.disabled = false;
        btn.textContent = label || '登录';
      }
    };
    btn.onclick = submit;
    const pwd = document.querySelector('#auth-pwd');
    if (pwd) pwd.onkeydown = e => { if (e.key === 'Enter') submit(); };
    const code = document.querySelector('#auth-code');
    if (code) code.onkeydown = e => { if (e.key === 'Enter') submit(); };

    const rb = document.querySelector('#um-rebind');
    if (rb) rb.onclick = () => {
      document.querySelector('#user-menu').classList.remove('open');
      rebindPhoneDialog();
    };
    const sw = document.querySelector('#um-switch');
    if (sw) sw.onclick = () => {
      document.querySelector('#user-menu').classList.remove('open');
      switchTenantDialog();
    };
    const out = document.querySelector('#um-logout');
    if (out) out.onclick = () => logout();
  }

  async function authenticate() {
    if (starting) return waitLogin();
    starting = true;
    bindLogin();
    try {
      await load();
    } catch (e) {
      clearAuth();
      showLogin('');
      return waitLogin();
    }
    hideLogin();
    return true;
  }

  /* ---------- 文件 ---------- */
  async function upload(file) {
    const fd = new FormData();
    fd.append('file', file);
    const r = await request('POST', '/files', fd);
    return r.id;
  }

  async function download(path) {
    const res = await fetch(BASE + path, { credentials: 'same-origin' });
    if (!res.ok) {
      toast('err', '下载失败 ' + res.status);
      return;
    }
    const blob = await res.blob();
    const cd = res.headers.get('Content-Disposition') || '';
    const m = /filename\*=UTF-8''([^;]+)/.exec(cd);
    const name = m ? decodeURIComponent(m[1]) : 'download.bin';
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  /* ---------- 写操作映射 ---------- */
  const flatFacilities = () => {
    const out = [];
    const walk = (nodes, parent) => (nodes || []).forEach(n => {
      out.push({ id: n.id, dbId: n.dbId, name: n.name, parent: parent || '—', level: n.level, version: n.version });
      if (n.children) walk(n.children, n.name);
    });
    walk((window.DB && DB.facilities) || [], '');
    return out;
  };

    /* 本地日期：toISOString 是 UTC，东八区凌晨会差一天 */
  const localDate = (d = new Date()) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  const today = () => localDate();
  const num = v => Number(v || 0);
  const range = v => String(v || '').split('~').map(x => x.trim());

  async function filesFromForm(form, key) {
    const box = form && form.querySelector(`[data-f="${key}"]`);
    const input = box && box.querySelector('input[type=file]');
    return input && input.files ? [...input.files] : [];
  }

  const devList = () => (window.DB && DB.devices) || [];
  const assetByName = (name) => ((window.DB && DB.assets) || []).find(a => a.name === name) || null;
  const isoDate = v => { const s = String(v || '').trim(); return /^\d{4}-\d{2}-\d{2}$/.test(s) ? s : ''; };
  const isoTime = v => {
    let s = String(v || '').trim().replace(' ', 'T');
    if (/^\d{4}-\d{2}-\d{2}$/.test(s)) s += 'T00:00:00';
    else if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(s)) s += ':00';
    return s;
  };
  const optNum = v => (v === '' || v === undefined || v === null ? null : Number(v));
  const must = (cond, msg) => { if (!cond) throw new Error(msg); };
  const thisYear = () => new Date().getFullYear();

  async function saveListPage(cfg, d, rec, editing, form) {
    const title = cfg.title;
    try {
      if (title === '产品列表') {
        must(d.name, '产品名称必填');
        must(d.type, '请选择产品类型');
        must(d.cls, '请选择产品分类');
        must(d.unit, '请选择单位');
        const body = {
          name: d.name, code: (rec && rec.code) || ('P' + Date.now().toString().slice(-9)),
          model: d.model || '', type: d.type, cls: d.cls, unit: d.unit,
          l1: optNum(d.l1), l2: optNum(d.l2), l3: optNum(d.l3),
          remark: d.remark || ''
        };
        if (editing) { body.version = rec.version; await request('PUT', '/products/' + rec.dbId, body); }
        else await request('POST', '/products', body);
        return afterSave('产品已保存');
      }
      if (title === '产品产值') {
        const p = (DB.products || []).find(x => x.name === d.product);
        if (!p) throw new Error('请选择产品');
        const body = {
          period: d.period, productId: p.dbId, value: num(d.value),
          unit: (rec && rec.unit) || '万元', qty: num(d.qty), qtyUnit: d.qtyUnit || '吨',
          remark: d.remark || ''
        };
        if (editing) { body.version = rec.version; await request('PUT', '/production-values/' + rec.dbId, body); }
        else await request('POST', '/production-values', body);
        return afterSave('产值已保存');
      }
      if (title === '生产总值') {
        const body = {
          period: d.period, name: d.name || '全厂生产总值', qty: num(d.qty),
          qtyUnit: d.qtyUnit || '吨', value: num(d.value), unit: (rec && rec.unit) || '万元',
          remark: d.remark || ''
        };
        if (editing) { body.version = rec.version; await request('PUT', '/total-values/' + rec.dbId, body); }
        else await request('POST', '/total-values', body);
        return afterSave('生产总值已保存');
      }
      if (title === '碳配额资产管理' || title === '信用资产管理') {
        must(d.name, '资产名称必填');
        must(d.qty !== '' && d.qty !== undefined, '资产数量必填');
        const isCredit = title === '信用资产管理';
        const body = {
          name: d.name,
          type: isCredit ? '信用资产' : (d.type || '配额资产'),
          qty: num(d.qty), unit: d.unit || 'tCO₂e',
          year: Number(d.year || (rec && rec.year) || thisYear()),
          status: d.status || (rec && rec.status) || '正常',
          remark: d.remark || ''
        };
        if (editing) { body.version = rec.version; await request('PUT', '/carbon-assets/' + rec.dbId, body); }
        else await request('POST', '/carbon-assets', body);
        return afterSave('资产已保存');
      }
      if (title === '交易管理') {
        if (editing) { toast('warn', '交易流水不支持修改，请删除后重新登记'); return true; }
        const a = assetByName(d.asset);
        must(a, '请选择资产');
        const body = {
          assetId: a.dbId, type: d.type || '购入', qty: num(d.qty), price: num(d.price),
          party: d.party || '', usePurpose: d.use || '', tradeDate: isoDate(d.date) || today()
        };
        await request('POST', '/carbon-trades', body);
        return afterSave('交易已登记');
      }
      if (title === '设备数据') {
        must(d.qty !== '' && d.qty !== undefined, '数值必填');
        if (editing) {
          const body = {
            version: rec.version, qty: num(d.qty), unit: d.unit, rate: num(d.rate),
            source: d.source || '手工补录', status: d.status || '正常', remark: d.remark || ''
          };
          await request('PUT', '/device-readings/' + rec.dbId, body);
        } else {
          const dev = devList().find(x => x.name === d.device);
          must(dev, '请选择设备');
          const body = {
            deviceId: dev.dbId, ts: isoTime(d.date), qty: num(d.qty), unit: d.unit,
            rate: num(d.rate), source: d.source || '手工补录', status: d.status || '正常',
            remark: d.remark || '', clientRequestId: 'ui-' + Date.now() + '-' + Math.floor(Math.random() * 1000)
          };
          await request('POST', '/device-readings', body);
        }
        return afterSave('读数已保存');
      }
      if (title === '事件预警') {
        must(d.name, '事件名称必填');
        const body = {
          name: d.name, type: d.type || '系统',
          metric: (rec && rec.metric) || '能耗', op: (rec && rec.op) || '>',
          threshold: optNum(d.threshold) == null ? 0 : optNum(d.threshold),
          level: (rec && rec.level) || 'mid',
          enabled: rec ? (rec.enabled ? 1 : 0) : 1,
          remark: d.remark || ''
        };
        if (editing) await request('PUT', '/alert-events/' + rec.dbId, body);
        else await request('POST', '/alert-events', body);
        return afterSave('事件已保存');
      }
      if (title === '节能改造项目') {
        must(d.name, '项目名称必填');
        const body = {
          projectYear: Number(d.year || (rec && rec.year) || thisYear()), name: d.name,
          invest: num(d.invest), investUnit: d.investUnit || '万元',
          saving: num(d.saving), savingUnit: d.savingUnit || 'tce/年',
          contract: d.contract || '否', done: d.done || '否', atDate: today(),
          remark: d.remark || ''
        };
        if (editing) { body.version = rec.version; await request('PUT', '/retrofits/' + rec.dbId, body); }
        else await request('POST', '/retrofits', body);
        return afterSave('改造项目已保存');
      }
      if (title === '能源管理情况') {
        must(d.org, '能源管理机构名称必填');
        must(d.duty, '能源管理负责人必填');
        const body = {
          atDate: isoDate(d.at) || today(), orgName: d.org, duty: d.duty,
          team: d.team || '', post: d.post || '', charge: d.charge || '', filler: d.filler || '',
          contact: d.contact || '', systemName: d.system || '', report: d.report || '',
          filing: d.record || '', instName: d.instName || '', stat: d.stat || '',
          promo: d.promo || '', training: d.training || '', subsidy: d.subsidy || '',
          remark: d.remark || ''
        };
        if (editing) { body.version = rec.version; await request('PUT', '/energy-mgmt/' + rec.dbId, body); }
        else await request('POST', '/energy-mgmt', body);
        return afterSave('能源管理情况已保存');
      }
      if (title === '供应链碳管理') {
        must(d.name, '供应商名称必填');
        must(d.product, '产品名称必填');
        must(d.unit, '产品单位未带入，请重新选择产品');
        must(d.footprint !== '' && d.footprint !== undefined, '碳足迹值必填');
        const body = {
          name: d.name, product: d.product, unit: d.unit, fpUnit: d.fpUnit || 'tCO₂e/t',
          footprint: num(d.footprint), date: isoDate(d.date) || today(),
          target: d.target === '' || d.target === undefined ? null : String(d.target),
          sort: Number(d.sort || 1),
          parentId: d.parentId ? Number(d.parentId) : null,
          remark: d.remark || null
        };
        if (editing) { body.version = rec.version; await request('PUT', '/suppliers/' + rec.dbId, body); }
        else await request('POST', '/suppliers', body);
        return afterSave('供应商已保存');
      }
      if (title === '原始凭证管理') {
        const [vf, vt] = range(d.valid);
        if (!vf) throw new Error('请选择有效期限');
        const uploaded = await Promise.all((await filesFromForm(form, 'files')).map(upload));
        const body = {
          name: d.name, code: d.code || '', description: d.desc || '',
          validFrom: vf, validTo: vt || null, atDate: today(),
          remark: d.remark || ''
        };
        if (uploaded.length) body.fileIds = uploaded;
        else if (!editing) body.fileIds = [];
        if (editing) { body.version = rec.version; await request('PUT', '/evidences/' + rec.dbId, body); }
        else await request('POST', '/evidences', body);
        return afterSave('凭证已保存');
      }
      if (title === '第三方报告管理') {
        const [vf, vt] = range(d.valid);
        if (!vf) throw new Error('请选择有效期限');
        const uploaded = await filesFromForm(form, 'file');
        const fileId = uploaded.length ? await upload(uploaded[0]) : (rec && rec.fileId);
        const body = {
          name: d.name, description: d.desc || '', validFrom: vf, validTo: vt || null,
          atDate: today(), fileId: fileId || null,
          remark: d.remark || ''
        };
        if (editing) { body.version = rec.version; await request('PUT', '/third-party-reports/' + rec.dbId, body); }
        else await request('POST', '/third-party-reports', body);
        return afterSave('第三方报告已保存');
      }
      if (title === '设施管理') {
        const parent = !d.parent || d.parent === '—' ? null
          : flatFacilities().find(f => f.name === d.parent);
        const body = {
          name: d.name, code: d.code, parentId: parent ? parent.dbId : null,
          sort: Number(d.sort || 1), remark: d.remark || ''
        };
        if (editing) { body.version = rec.version; await request('PUT', '/facilities/' + rec.dbId, body); }
        else await request('POST', '/facilities', body);
        return afterSave('设施已保存');
      }
      return false;
    } catch (e) {
      toast('err', e.message + (e.detail && e.detail.currentVersion != null
        ? '（当前版本 ' + e.detail.currentVersion + '，请刷新）' : ''));
      return true;
    }
  }

  async function afterSave(msg) {
    await load();
    UI.render();
    toast('ok', msg);
    return true;
  }

  const DELETE_BASE = {
    '产品列表': '/products/',
    '产品产值': '/production-values/',
    '生产总值': '/total-values/',
    '碳配额资产管理': '/carbon-assets/',
    '信用资产管理': '/carbon-assets/',
    '交易管理': '/carbon-trades/',
    '原始凭证管理': '/evidences/',
    '第三方报告管理': '/third-party-reports/',
    '设施管理': '/facilities/',
    '设备数据': '/device-readings/',
    '事件预警': '/alert-events/',
    '节能改造项目': '/retrofits/',
    '能源管理情况': '/energy-mgmt/',
    '供应链碳管理': '/suppliers/'
  };

  async function deleteRows(cfg, rows) {
    const base = DELETE_BASE[cfg.title];
    if (!base) { toast('warn', '该页暂不提供删除', cfg.title); return { ok: 0, fail: rows.length }; }
    let ok = 0, fail = 0;
    for (const r of rows) {
      if (!r || !r.dbId) { fail++; continue; }
      try { await request('DELETE', base + r.dbId); ok++; } catch (e) { fail++; }
    }
    return { ok, fail };
  }

  async function deleteRow(cfg, rec) {
    const base = DELETE_BASE[cfg.title];
    if (!base) {
      toast('warn', '该页暂不提供删除', cfg.title);
      return true;
    }
    try {
      await request('DELETE', base + rec.dbId);
      await load();
      UI.render();
      toast('ok', '已删除');
    } catch (e) {
      toast('err', e.message);
    }
    return true;
  }

  async function exportResource(cfg) {
    const map = {
      '产品列表': 'products',
      '产品产值': 'production-values',
      '生产总值': 'total-values',
      '碳配额资产管理': 'carbon-assets',
      '信用资产管理': 'credit-assets',
      '交易管理': 'carbon-trades',
      '节能改造项目': 'retrofits',
      '能源管理情况': 'energy-mgmt',
      '原始凭证管理': 'evidences',
      '第三方报告管理': 'third-party-reports',
      '设施管理': 'facilities',
      '设备数据': 'readings',
      '碳足迹核算': 'footprints',
      '供应链碳管理': 'suppliers',
      '预警记录': 'alert-records'
    };
    const resource = map[cfg.title];
    if (!resource) {
      toast('warn', '该页导出尚未接入后端', cfg.title);
      return true;
    }
    await download('/export?resource=' + resource);
    return true;
  }

  window.CarbonAPI = {
    authenticate, refresh, reloadQuiet, request, upload, download, saveListPage, deleteRow, exportResource,
    logout, switchTenant: switchTenantDialog, roleLabel, rebindPhone: rebindPhoneDialog,
    setUser: (u) => { me = u; },
    me: () => me,
    isAdmin, canWrite, canReview, canApprove,
    deleteRows
  };
})();
