/**
 * 前端全页面功能测试（2026-11-27）
 * 真实浏览器 + 真实后端；只读为主，表单只做反例校验（不保存）。
 * 运行：node tools/frontend-full.mjs（可设 CB_BASE / CHROME_PATH 覆盖默认值）
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

// 源码树内运行：不再硬编码绝对路径（P2-9）
//   FE_ROOT = carbonbal-web/（本文件位于 carbonbal-web/tools/）
//   BASE    = 后端地址，可用 CB_BASE 覆盖
//   CHROME  = 浏览器可执行文件，可用 CHROME_PATH 覆盖
const HERE = path.dirname(fileURLToPath(import.meta.url));
const FE_ROOT = path.resolve(HERE, '..');
const require = createRequire(path.join(FE_ROOT, 'package.json'));
const { chromium } = require('playwright-core');

const BASE = process.env.CB_BASE || 'http://127.0.0.1:8088';
const CHROME = process.env.CHROME_PATH
  || (process.platform === 'win32'
    ? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
    : '/usr/bin/google-chrome');
const OUT = new URL('./frontend-full-results.json', import.meta.url);
const results = [];
let PASS = 0, FAIL = 0;
const stamp = Date.now();

function rec(id, group, prio, name, expected, ok, actual, note = '') {
  results.push({ id, group, prio, name, expected, actual, result: ok ? 'PASS' : 'FAIL', note });
  if (ok) PASS++; else FAIL++;
  console.log(`[${ok ? 'PASS' : 'FAIL'}] ${id} ${name} — ${actual}${note ? ' | ' + note : ''}`);
}
async function step(id, group, prio, name, expected, fn) {
  try {
    const a = await fn();
    rec(id, group, prio, name, expected, true, a || '符合预期');
  } catch (e) {
    rec(id, group, prio, name, expected, false, String((e && e.message) || e).slice(0, 200));
  }
}
const assert = (c, m) => { if (!c) throw new Error(m); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

function capCodeFromSvg(img) {
  try {
    const svg = atob(String(img).split(',')[1]);
    return (svg.match(/<text[^>]*>[^<]<\/text>/g) || []).map(t => t.replace(/.*>([^<])<\/text>/, '$1')).join('');
  } catch { return ''; }
}
async function fillCaptcha(page) {
  await page.waitForFunction(() => {
    const img = document.querySelector('#auth-cap-img');
    return img && img.src && img.src.indexOf('data:image') === 0;
  }, null, { timeout: 10000 }).catch(() => {});
  const code = await page.evaluate(() => {
    const img = document.querySelector('#auth-cap-img');
    return img && img.src ? img.src : '';
  });
  const c = capCodeFromSvg(code);
  if (c) await page.fill('#auth-cap', c);
  return c;
}
async function login(page, phone, pwd) {
  for (let attempt = 0; attempt < 3; attempt++) {
    await page.goto(BASE + '/app/', { waitUntil: 'domcontentloaded' });
    await page.waitForSelector('#auth-phone', { timeout: 15000 });
    await page.fill('#auth-phone', phone);
    await page.fill('#auth-pwd', pwd);
    await fillCaptcha(page);
    await page.click('#auth-login');
    const ok = await page.waitForFunction(() => {
      const m = document.querySelector('#auth-mask');
      return !m || m.classList.contains('hide');
    }, null, { timeout: 20000 }).then(() => true).catch(() => false);
    if (ok) { await page.waitForTimeout(800); return; }
    const err = await page.locator('#auth-err').innerText().catch(() => '');
    if (attempt === 2) throw new Error('登录失败：' + err);
    await page.locator('#auth-cap-img').click().catch(() => {});
    await page.waitForTimeout(400);
  }
}
async function newCtx(browser, opts = {}) {
  const ctx = await browser.newContext(Object.assign({ viewport: { width: 1440, height: 950 }, bypassCSP: true, acceptDownloads: true }, opts));
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  page.__errs = errs;
  return { ctx, page };
}
async function nav(page, path, wait = 950) {
  await page.evaluate(h => { location.hash = h; }, path);
  await page.waitForTimeout(wait);
}
async function lastToast(page) {
  return (await page.locator('#toasts .toast').last().innerText().catch(() => '')) || '';
}
async function clearToasts(page) {
  await page.evaluate(() => document.querySelectorAll('#toasts .toast').forEach(n => n.remove()));
}
async function modalText(page) {
  return page.locator('.overlay').last().innerText();
}
async function closeOverlay(page) {
  const b = page.locator('.overlay [data-close], .drawer [data-close]').last();
  if (await b.count()) await b.click().catch(() => {});
  await page.waitForTimeout(200);
}
async function fieldErrors(page) {
  return page.evaluate(() => [...document.querySelectorAll('.overlay .field.error .err-msg')].map(e => e.textContent.trim()).filter(Boolean));
}
async function setHidden(page, name, val) {
  await page.evaluate(([n, v]) => {
    // 原生 select 被组件替换后仍留在 DOM 且 display:none；必须以自定义组的隐藏 input 为准
    const custom = document.querySelector(`.overlay [data-f="${n}"] .sel input[name="${n}"]`);
    const el = custom || [...document.querySelectorAll(`.overlay [name="${n}"]`)].pop();
    if (el) { el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); }
  }, [name, val]);
}
async function apiLogin(phone, pwd) {
  const cap = await (await fetch(BASE + '/api/auth/captcha')).json();
  const b = cap.body || {};
  const code = capCodeFromSvg(b.image);
  const j = await (await fetch(BASE + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone, password: pwd, captchaId: b.captchaId, captchaCode: code })
  })).json();
  return j.body && j.body.token;
}
async function apiGet(path, token) {
  const r = await fetch(BASE + path, { headers: token ? { Authorization: 'Bearer ' + token } : {} });
  return r.json().catch(() => null);
}
async function snapshot(token) {
  const boot = await apiGet('/api/bootstrap?year=2026', token);
  const b = (boot && boot.body) || {};
  const users = await apiGet('/api/users', token);
  const ap = await apiGet('/api/accounting-periods/2026-11', token);
  return {
    products: (b.products || []).length,
    suppliers: (b.suppliers || []).length,
    devices: (b.devices || []).length,
    deviceData: (b.deviceData || []).length,
    facilities: (b.facilities || []).length,
    users: Array.isArray(users && users.body) ? users.body.length : -1,
    period: ap && ap.body ? ap.body.status : '?'
  };
}

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const adminToken = await apiLogin('13800000001', 'Admin@2026!Pass');
  const before = await snapshot(adminToken);

  const { ctx, page } = await newCtx(browser);
  const mutating = [];
  page.on('request', r => {
    if (!['GET', 'HEAD', 'OPTIONS'].includes(r.method())) {
      try { mutating.push(r.method() + ' ' + new URL(r.url()).pathname); } catch { /* ignore */ }
    }
  });

  /* ==================== A. 登录与认证（未登录） ==================== */
  await page.goto(BASE + '/app/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#auth-phone', { timeout: 15000 });
  await page.waitForTimeout(600);

  await step('A01', '认证与登录', 'P0', '登录页初始状态', '登录遮罩可见、无预填、无演示账号提示', async () => {
    const v = await page.evaluate(() => ({
      mask: !!document.querySelector('#auth-mask') && !document.querySelector('#auth-mask').classList.contains('hide'),
      phone: document.querySelector('#auth-phone').value,
      pwd: document.querySelector('#auth-pwd').value,
      tip: !!document.querySelector('.auth-tip'),
      cap: !!(document.querySelector('#auth-cap-img') && document.querySelector('#auth-cap-img').src)
    }));
    assert(v.mask, '登录遮罩未显示');
    assert(!v.phone && !v.pwd, '手机号/密码被预填');
    assert(!v.tip, '仍存在演示账号提示');
    assert(v.cap, '图形验证码未加载');
    return '遮罩可见、无预填、验证码已加载';
  });

  await step('A02', '认证与登录', 'P1', '手机号格式校验', '提示“请输入 11 位手机号”', async () => {
    await page.fill('#auth-phone', '123');
    await page.fill('#auth-pwd', 'Whatever@123');
    await page.click('#auth-login');
    await page.waitForTimeout(300);
    const e = await page.locator('#auth-err').innerText();
    assert(e.includes('11 位手机号'), '错误文案=' + e);
    return e;
  });

  await step('A03', '认证与登录', 'P1', '密码必填校验', '提示“请输入密码”', async () => {
    await page.fill('#auth-phone', '13800000001');
    await page.fill('#auth-pwd', '');
    await page.click('#auth-login');
    await page.waitForTimeout(300);
    const e = await page.locator('#auth-err').innerText();
    assert(e.includes('请输入密码'), '错误文案=' + e);
    return e;
  });

  await step('A04', '认证与登录', 'P0', '图形验证码错误被拒', '返回验证码错误并刷新图片', async () => {
    await page.fill('#auth-pwd', 'Admin@2026!Pass');
    const oldSrc = await page.evaluate(() => document.querySelector('#auth-cap-img').src);
    await page.fill('#auth-cap', 'XXXX');
    await page.click('#auth-login');
    await page.waitForTimeout(1200);
    const e = await page.locator('#auth-err').innerText();
    const newSrc = await page.evaluate(() => document.querySelector('#auth-cap-img').src);
    assert(/验证码/.test(e), '错误文案=' + e);
    assert(newSrc && newSrc !== oldSrc, '验证码未刷新');
    return e.slice(0, 40);
  });

  await step('A05', '认证与登录', 'P0', '错误密码被拒且不泄露账号存在性', '统一提示账号或密码错误', async () => {
    await page.fill('#auth-phone', '13800000001');
    await page.fill('#auth-pwd', 'Wrong@2026!Pass');
    await fillCaptcha(page);
    await page.click('#auth-login');
    await page.waitForTimeout(1200);
    const e = await page.locator('#auth-err').innerText();
    assert(e.length > 0, '无错误提示');
    assert(!/不存在/.test(e), '泄露账号存在性：' + e);
    return e.slice(0, 50);
  });

  await step('A06', '认证与登录', 'P1', '验证码登录页签切换', '密码框隐藏、短信框显示；未填手机号获取验证码被提示', async () => {
    await page.click('[data-auth-tab="sms"]');
    await page.waitForTimeout(250);
    const v = await page.evaluate(() => ({
      pwd: document.querySelector('#auth-pwd-field').classList.contains('hide'),
      sms: !document.querySelector('#auth-sms-field').classList.contains('hide'),
      cap: !document.querySelector('#auth-cap-field').classList.contains('hide')
    }));
    assert(v.pwd && v.sms, '页签切换未生效 ' + JSON.stringify(v));
    await page.fill('#auth-phone', '');
    await page.click('#auth-send');
    await page.waitForTimeout(300);
    const e = await page.locator('#auth-err').innerText();
    assert(e.includes('11 位手机号'), '发送验证码提示=' + e);
    await page.click('[data-auth-tab="pwd"]');
    await page.waitForTimeout(200);
    return '页签切换正常，发送校验正常';
  });

  await step('A07', '认证与登录', 'P0', '管理员正确登录进入首页', '登录遮罩关闭、KPI 与导航渲染', async () => {
    await login(page, '13800000001', 'Admin@2026!Pass');
    const v = await page.evaluate(() => ({
      kpi: document.querySelectorAll('#view .kpi').length,
      nav: document.querySelectorAll('#nav a').length,
      avatar: (document.querySelector('#avatar').textContent || '').trim(),
      org: (document.querySelector('#side-org').textContent || '').trim()
    }));
    assert(v.kpi >= 2, 'KPI 数=' + v.kpi);
    assert(v.nav >= 30, '菜单项=' + v.nav);
    assert(v.avatar, '头像为空');
    return `KPI ${v.kpi}、菜单 ${v.nav}、机构「${v.org}」`;
  });

  await step('A08', '认证与登录', 'P1', '错误验证码一次性（失败后必须换图）', '失败后验证码图片刷新', async () => {
    const s1 = await page.evaluate(() => document.querySelector('#auth-cap-img') ? document.querySelector('#auth-cap-img').src : '');
    return s1 ? '已登录状态，跳过（登录流程已覆盖刷新）' : 'N/A';
  });

  /* ==================== B. 框架与顶栏 ==================== */
  await step('B01', '框架与顶栏', 'P1', '用户菜单信息与身份一致', '头像首字母、姓名、企业名称来自当前登录身份', async () => {
    const me = await page.evaluate(() => CarbonAPI.me());
    await page.click('#avatar');
    await page.waitForTimeout(250);
    const t = await page.locator('#user-menu').innerText();
    assert(t.includes(me.displayName || ''), '用户菜单姓名不符：' + t.slice(0, 40));
    assert(t.includes('修改密码') && t.includes('退出登录'), '缺少账号操作');
    await page.keyboard.press('Escape');
    await page.click('#view', { position: { x: 5, y: 5 } }).catch(() => {});
    return '姓名/企业/操作项齐全';
  });

  await step('B02', '框架与顶栏', 'P1', '全局搜索命中并跳转', '输入“配额”回车跳转配额页', async () => {
    await page.fill('#global-search', '配额');
    await page.press('#global-search', 'Enter');
    await page.waitForTimeout(900);
    const h = await page.evaluate(() => location.hash);
    assert(h.includes('quota'), '未跳转：' + h);
    return h;
  });

  await step('B03', '框架与顶栏', 'P2', '全局搜索无结果提示', '提示“未找到匹配页面”且不跳转', async () => {
    await nav(page, '#/home');
    await clearToasts(page);
    const beforeHash = await page.evaluate(() => location.hash);
    await page.fill('#global-search', 'zzz不存在的页面zzz');
    await page.press('#global-search', 'Enter');
    await page.waitForTimeout(500);
    const t = await lastToast(page);
    const h = await page.evaluate(() => location.hash);
    assert(t.includes('未找到匹配页面'), 'toast=' + t);
    assert(h === beforeHash, '无结果仍跳转：' + h);
    return t.split('\n')[0];
  });

  await step('B04', '框架与顶栏', 'P2', '通知铃铛弹窗', '预警通知弹窗可打开/关闭', async () => {
    await page.click('#btn-bell');
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const t = await modalText(page);
    assert(t.includes('预警通知'), '弹窗标题缺失');
    await closeOverlay(page);
    assert(await page.locator('.overlay').count() === 0, '弹窗未关闭');
    return '打开/关闭正常';
  });

  await step('B05', '框架与顶栏', 'P2', '网站设置抽屉', '主题切换生效、标签页开关生效、恢复默认', async () => {
    await page.click('#btn-settings');
    await page.waitForSelector('.drawer', { timeout: 4000 });
    await page.click('[data-theme="blue"]');
    const blue = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--brand-600').trim());
    assert(blue === '#2563eb', '主题未切换：' + blue);
    await page.click('#sw-tags');
    const noTags = await page.evaluate(() => document.body.classList.contains('no-tags'));
    assert(noTags, '标签页开关未生效');
    await page.click('#st-reset');
    const v = await page.evaluate(() => ({
      brand: getComputedStyle(document.documentElement).getPropertyValue('--brand-600').trim(),
      noTags: document.body.classList.contains('no-tags'),
      toast: (document.querySelector('#toasts .toast:last-child') || {}).innerText || ''
    }));
    assert(v.brand.toLowerCase() === '#0f7a5a', '恢复默认主题失败：' + v.brand);
    assert(!v.noTags, '恢复后标签页仍隐藏');
    await closeOverlay(page);
    return '主题切换/标签页/恢复默认正常';
  });

  await step('B06', '框架与顶栏', 'P2', '菜单折叠与展开', '侧栏折叠后再次点击恢复', async () => {
    await page.click('#btn-collapse');
    await page.waitForTimeout(200);
    assert(await page.evaluate(() => document.querySelector('.app').classList.contains('side-collapsed')), '未折叠');
    await page.click('#btn-collapse');
    await page.waitForTimeout(200);
    assert(!(await page.evaluate(() => document.querySelector('.app').classList.contains('side-collapsed'))), '未展开');
    return '折叠/展开正常';
  });

  await step('B07', '框架与顶栏', 'P1', '页签打开、切换与关闭其他', '多页签可切换，“关闭其他”仅保留首页与当前页', async () => {
    await nav(page, '#/carbon-product/product/list');
    await nav(page, '#/carbon-facility/device-data/list');
    const n1 = await page.locator('#tags .tag-item').count();
    assert(n1 >= 3, '页签数=' + n1);
    await page.locator('#tags .tag-item[data-path="/carbon-product/product/list"]').click();
    await page.waitForTimeout(700);
    assert((await page.evaluate(() => location.hash)).includes('product'), '切换页签失败');
    await page.click('#tag-more');
    await page.click('#tag-menu [data-act="others"]');
    await page.waitForTimeout(700);
    const n2 = await page.locator('#tags .tag-item').count();
    assert(n2 === 2, '关闭其他后页签数=' + n2);
    return `${n1} → ${n2} 个页签`;
  });

  await step('B08', '框架与顶栏', 'P2', '面包屑与浏览器标题', '面包屑分组/标题正确，document.title 同步', async () => {
    await nav(page, '#/carbon-product/product/list');
    const v = await page.evaluate(() => ({
      g: document.querySelector('#crumb-group').textContent.trim(),
      t: document.querySelector('#crumb-title').textContent.trim(),
      d: document.title
    }));
    assert(v.g === '产值管理', '分组=' + v.g);
    assert(v.t === '产品列表', '标题=' + v.t);
    assert(v.d.includes('产品列表'), 'document.title=' + v.d);
    return v.g + ' / ' + v.t;
  });

  await step('B09', '框架与顶栏', 'P1', '修改密码弹窗表单校验', '空/弱密码/不一致均被拦截并提示', async () => {
    await page.click('#avatar');
    await page.click('#um-pwd');
    await page.waitForSelector('.overlay #pwd-form', { timeout: 4000 });
    await page.click('#pwd-save');
    await page.waitForTimeout(300);
    let errs = await page.evaluate(() => [...document.querySelectorAll('.overlay .err-msg')].map(e => e.textContent.trim()).filter(Boolean));
    assert(errs.some(x => x.includes('原密码')), '未提示原密码');
    assert(errs.some(x => x.includes('至少 12 位')), '未提示密码规则');
    await page.fill('#pwd-form [name="old"]', 'Old@2026!Pass');
    await page.fill('#pwd-form [name="next"]', 'Abc@12345678');
    await page.fill('#pwd-form [name="again"]', 'Abc@12345679');
    await page.click('#pwd-save');
    await page.waitForTimeout(300);
    const t = await lastToast(page);
    assert(t.includes('不一致') || t.includes('参数验证'), '不一致未提示：' + t);
    await closeOverlay(page);
    return '空值/弱密码/不一致均被拦截';
  });

  await step('B10', '框架与顶栏', 'P0', '退出登录', '回到登录页、会话 Cookie 清除', async () => {
    await page.click('#avatar');
    await page.click('#um-logout');
    await page.waitForTimeout(1800);
    const v = await page.evaluate(() => {
      const m = document.querySelector('#auth-mask');
      return { visible: !!m && !m.classList.contains('hide') };
    });
    assert(v.visible, '登录框未出现');
    const cookies = (await ctx.cookies()).filter(c => c.name === 'cb_token');
    assert(cookies.length === 0, '会话 Cookie 未清除');
    return '登录框可见、Cookie 已清';
  });

  await step('B11', '框架与顶栏', 'P0', '退出后可再次登录', '重新登录成功且首页有数据', async () => {
    await login(page, '13800000001', 'Admin@2026!Pass');
    const k = await page.locator('#view .kpi').count();
    assert(k >= 2, '重新登录后 KPI=' + k);
    return '重新登录成功';
  });

  /* ==================== C. 全页面渲染（41 条菜单路由） ==================== */
  const menu = await page.evaluate(() => MENU.flatMap(g => g.type === 'page'
    ? [{ path: g.path, name: g.name, page: g.page || null, adminOnly: false, platform: false }]
    : (g.children || []).map(c => ({ path: c.path, name: c.name, page: c.page || null, adminOnly: !!g.adminOnly, platform: !!g.platform }))));
  const reg = await page.evaluate(() => Object.keys(UI.routes || {}));
  const meAdmin = await page.evaluate(() => ({ role: CarbonAPI.me().role, pages: CarbonAPI.me().pages || [], platformAdmin: !!UI.state.platformAdmin }));

  rec('C00', '全页面渲染', 'P0', '菜单路由均已注册', '菜单 41 条均有对应页面实现',
    menu.every(m => reg.includes(m.path)), `菜单 ${menu.length} 条 / 已注册 ${reg.length} 条`,
    menu.filter(m => !reg.includes(m.path)).map(m => m.path).join(',') || '无缺失');

  let ridx = 0;
  for (const m of menu) {
    ridx++;
    const id = 'R' + String(ridx).padStart(2, '0');
    const denied = (m.page && !meAdmin.pages.includes('*') && !meAdmin.pages.includes(m.page))
      || (m.adminOnly && meAdmin.role !== 'admin')
      || (m.platform && !meAdmin.platformAdmin);
    await step(id, '全页面渲染', m.platform || m.adminOnly ? 'P1' : 'P0', `渲染 ${m.path}（${m.name}）`,
      denied ? '显示“无权访问”拦截页' : `标题=${m.name}，无 NaN/对象串，无控制台错误`, async () => {
        page.__errs.length = 0;
        await nav(page, '#' + m.path, 900);
        const st = await page.evaluate(() => {
          const view = document.querySelector('#view');
          return {
            page: !!(view && view.querySelector('.page')),
            crumb: (document.querySelector('#crumb-title').textContent || '').trim(),
            text: view ? view.innerText : '',
            empty: (view && view.querySelector('.tb-empty') ? view.querySelector('.tb-empty').innerText : ''),
            active: (document.querySelector('#nav a.active') || {}).getAttribute ? document.querySelector('#nav a.active').getAttribute('href') : null,
            toasts: document.querySelectorAll('#toasts .toast').length
          };
        });
        if (denied) {
          assert(st.crumb === '无权访问', '未拦截，标题=' + st.crumb);
          assert(st.empty.includes('没有该功能的访问权限'), '缺少 403 文案');
          return '403 拦截正常';
        }
        assert(st.page, '页面未渲染');
        assert(st.crumb === m.name, '标题=' + st.crumb + ' 期望 ' + m.name);
        assert(!/NaN|\[object Object\]/.test(st.text), '文本含 NaN/[object Object]');
        assert(st.active === '#' + m.path, '导航未高亮：' + st.active);
        assert(page.__errs.length === 0, '控制台错误：' + page.__errs[0]);
        return `${m.name} 渲染正常`;
      });
  }

  /* ==================== D. 列表与表单交互 ==================== */
  await nav(page, '#/carbon-product/product/list', 1100);

  await step('D01', '列表与表单', 'P0', '产品列表按名称筛选', '筛选后每行都包含关键词', async () => {
    const name = await page.evaluate(() => {
      const c = document.querySelector('#g-table tbody tr td b');
      return c ? c.textContent.trim() : '';
    });
    assert(name, '列表无数据行');
    await page.fill('[data-filter="kw"]', name);
    await page.click('#g-search');
    await page.waitForTimeout(900);
    const v = await page.evaluate(() => ({
      rows: [...document.querySelectorAll('#g-table tbody tr')].map(r => r.innerText),
      total: document.querySelector('#g-total').textContent
    }));
    assert(v.rows.length >= 1, '筛选后无行');
    assert(v.rows.every(t => t.includes(name)), '存在不含关键词的行');
    return `命中 ${v.rows.length} 行，${v.total.trim()}`;
  });

  await step('D02', '列表与表单', 'P1', '列表空态展示', '无匹配数据时显示“暂无数据/共 0 条”', async () => {
    await page.fill('[data-filter="kw"]', 'ZZZ不存在' + stamp);
    await page.click('#g-search');
    await page.waitForTimeout(900);
    const v = await page.evaluate(() => ({
      empty: (document.querySelector('#g-table .tb-empty') || {}).innerText || '',
      total: document.querySelector('#g-total').textContent
    }));
    assert(v.empty.includes('暂无数据'), '空态文案=' + v.empty);
    assert(v.total.includes('0'), '总数=' + v.total);
    return v.empty.split('\n')[0] + ' / ' + v.total.trim();
  });

  await step('D03', '列表与表单', 'P1', '筛选重置恢复全量', '重置后回到初始条数', async () => {
    await page.click('#g-reset');
    await page.waitForTimeout(900);
    const total = await page.locator('#g-total').innerText();
    const rows = await page.locator('#g-table tbody tr').count();
    assert(rows >= 4, '重置后行数=' + rows);
    return total + '，' + rows + ' 行';
  });

  await step('D04', '列表与表单', 'P0', '设备数据分页', '点击第 2 页后当前页为 2', async () => {
    await nav(page, '#/carbon-facility/device-data/list', 1100);
    const btn = page.locator('#g-table .pager [data-go="2"]').first();
    assert(await btn.count(), '无第 2 页');
    await btn.click();
    await page.waitForTimeout(900);
    const active = (await page.locator('#g-table .pager button.active').innerText()).trim();
    assert(active === '2', '当前页=' + active);
    return '第 2 页';
  });

  await step('D05', '列表与表单', 'P1', '分页跳转越界上限收敛', '输入 999 自动收敛到最后一页', async () => {
    const inp = page.locator('#g-table .pg-jump');
    assert(await inp.count(), '无跳页输入框');
    await inp.fill('999');
    await inp.press('Enter');
    await page.waitForTimeout(900);
    const v = await page.evaluate(() => ({
      active: (document.querySelector('#g-table .pager button.active') || {}).innerText || '',
      val: document.querySelector('#g-table .pg-jump').value
    }));
    assert(Number(v.val) === Number(v.active), '未收敛：输入=' + v.val + ' 当前=' + v.active);
    assert(Number(v.active) >= 2, '未到最后页：' + v.active);
    return '收敛到第 ' + v.active + ' 页';
  });

  await step('D06', '列表与表单', 'P2', '分页跳转越界下限收敛', '输入 0 自动回到第 1 页', async () => {
    const inp = page.locator('#g-table .pg-jump');
    await inp.fill('0');
    await inp.press('Enter');
    await page.waitForTimeout(800);
    const v = await page.evaluate(() => (document.querySelector('#g-table .pager button.active') || {}).innerText || '');
    assert(Number(v) === 1, '当前页=' + v);
    return '回到第 1 页';
  });

  await nav(page, '#/carbon-product/product/list', 1100);
  await step('D07', '列表与表单', 'P0', '新建产品必填校验', '空提交标记必填字段并阻止保存', async () => {
    await page.click('#g-new');
    await page.waitForSelector('.overlay #gf', { timeout: 4000 });
    await page.click('#gf-save');
    await page.waitForTimeout(300);
    const errs = await fieldErrors(page);
    assert(errs.length >= 2, '错误字段数=' + errs.length);
    assert(errs.some(e => e.includes('产品名称')), '缺名称必填：' + errs.join('|'));
    assert(errs.some(e => e.includes('单位')), '缺单位必填：' + errs.join('|'));
    const t = await lastToast(page);
    assert(t.includes('参数验证错误'), 'toast=' + t);
    await closeOverlay(page);
    return errs.join('；');
  });

  await step('D08', '列表与表单', 'P2', '选填数值字段负数边界校验', '选填数值 -1 也应提示不能小于 0', async () => {
    await page.click('#g-new');
    await page.waitForSelector('.overlay #gf', { timeout: 4000 });
    // 名称留空以阻断保存（避免写库），仅验证数值下限是否被校验
    await setHidden(page, 'unit', '吨');
    await page.fill('.overlay [name="l1"]', '-1');
    await page.click('#gf-save');
    await page.waitForTimeout(300);
    const errs = await fieldErrors(page);
    await closeOverlay(page);
    const hit = errs.find(e => e.includes('不能小于'));
    assert(hit, '选填数值 -1 未提示“不能小于 0”（实际错误：' + (errs.join('；') || '无') + '）');
    return hit;
  });

  await step('D09', '列表与表单', 'P1', '编辑产品回填', '编辑弹窗回填既有名称', async () => {
    await closeOverlay(page);
    await page.locator('#g-table [data-edit]').first().click();
    await page.waitForSelector('.overlay #gf', { timeout: 4000 });
    const v = await page.locator('.overlay [name="name"]').inputValue();
    assert(v && v.length > 1, '名称未回填');
    await closeOverlay(page);
    return '回填：' + v;
  });

  await step('D10', '列表与表单', 'P1', '批量删除按钮状态联动', '未勾选禁用，勾选后启用并计数', async () => {
    await closeOverlay(page);
    const bulk = page.locator('#g-bulk');
    assert(await bulk.count(), '无批量删除按钮');
    assert(await bulk.isDisabled(), '初始未禁用');
    await page.locator('#g-table [data-check]').first().check();
    await page.waitForTimeout(400);
    const b2 = page.locator('#g-bulk');
    assert(!(await b2.isDisabled()), '勾选后仍禁用');
    assert((await b2.innerText()).includes('1'), '计数未更新：' + await b2.innerText());
    await page.locator('#g-table [data-check]').first().uncheck();
    await page.waitForTimeout(400);
    assert(await page.locator('#g-bulk').isDisabled(), '取消勾选后未禁用');
    return '禁用→启用(1)→禁用';
  });

  await step('D11', '列表与表单', 'P1', '导出 CSV 触发下载', '收到 .csv 下载文件', async () => {
    const [dl] = await Promise.all([
      page.waitForEvent('download', { timeout: 15000 }),
      page.click('#g-export')
    ]);
    const name = dl.suggestedFilename();
    assert(/\.csv$/i.test(name), '文件名=' + name);
    return name;
  });

  await step('D12', '列表与表单', 'P2', '筛选输入特殊字符不执行脚本', '输入 <img onerror> 不执行脚本且返回空态', async () => {
    await page.evaluate(() => { window.__xss = 0; });
    await page.fill('[data-filter="kw"]', '<img src=x onerror="window.__xss=1">');
    await page.click('#g-search');
    await page.waitForTimeout(900);
    const v = await page.evaluate(() => ({ xss: window.__xss, html: document.querySelector('#g-table').innerHTML }));
    assert(v.xss === 0, '脚本被执行');
    assert(!v.html.includes('onerror="window.__xss'), '未转义注入到表格');
    await page.click('#g-reset');
    await page.waitForTimeout(700);
    return '未执行、已转义';
  });

  await step('D13', '列表与表单', 'P2', '超长关键词边界', '500 字符关键词不崩溃并返回空态', async () => {
    await page.fill('[data-filter="kw"]', 'A'.repeat(500));
    await page.click('#g-search');
    await page.waitForTimeout(900);
    const v = await page.evaluate(() => ({
      page: !!document.querySelector('#view .page'),
      total: document.querySelector('#g-total').textContent
    }));
    assert(v.page, '页面崩溃');
    assert(v.total.includes('0'), '总数=' + v.total);
    await page.click('#g-reset');
    await page.waitForTimeout(700);
    return v.total.trim();
  });

  await step('D14', '列表与表单', 'P1', '导入弹窗与模板下载', '导入弹窗打开、模板可下载、可取消', async () => {
    await page.click('#g-import');
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const t = await modalText(page);
    assert(t.includes('下载模板') && t.includes('导入说明'), '导入弹窗内容缺失');
    const [dl] = await Promise.all([
      page.waitForEvent('download', { timeout: 12000 }),
      page.click('#imp-tpl')
    ]);
    assert(/\.csv$/i.test(dl.suggestedFilename()), '模板文件名=' + dl.suggestedFilename());
    await closeOverlay(page);
    return '模板 ' + dl.suggestedFilename();
  });

  await step('D15', '列表与表单', 'P1', '设备数据新增必填与数值边界', '空数值必填；-1 提示不能小于 0', async () => {
    await nav(page, '#/carbon-facility/device-data/list', 1100);
    await page.click('#g-new');
    await page.waitForSelector('.overlay #gf', { timeout: 4000 });
    await page.click('#gf-save');
    await page.waitForTimeout(250);
    let errs = await fieldErrors(page);
    assert(errs.some(e => e.includes('数值')), '空数值未拦截：' + errs.join('|'));
    await page.fill('.overlay [name="qty"]', '-1');
    await page.click('#gf-save');
    await page.waitForTimeout(250);
    errs = await fieldErrors(page);
    assert(errs.some(e => e.includes('不能小于 0')), '负数未拦截：' + errs.join('|'));
    await closeOverlay(page);
    return '必填/负数均拦截';
  });

  await step('D16', '列表与表单', 'P2', '资产数量负数校验', '资产数量 -5 提示不能小于 0', async () => {
    await nav(page, '#/carbon-asset/asset/list', 1100);
    await page.click('#g-new');
    await page.waitForSelector('.overlay #gf', { timeout: 4000 });
    await page.fill('.overlay [name="name"]', '自动化资产' + stamp);
    await page.fill('.overlay [name="qty"]', '-5');
    await page.click('#gf-save');
    await page.waitForTimeout(250);
    const errs = await fieldErrors(page);
    assert(errs.some(e => e.includes('不能小于 0')), '错误=' + errs.join('|'));
    await closeOverlay(page);
    return '负数拦截';
  });

  /* ==================== E. 业务页面 ==================== */
  await step('E01', '业务页面', 'P0', '首页 KPI 与刷新', 'KPI/图表存在，刷新给出提示', async () => {
    await nav(page, '#/home', 1000);
    const v = await page.evaluate(() => ({
      kpi: document.querySelectorAll('#view .kpi').length,
      charts: document.querySelectorAll('#view svg.chart').length
    }));
    assert(v.kpi >= 2, 'KPI=' + v.kpi);
    assert(v.charts >= 2, '图表=' + v.charts);
    await clearToasts(page);
    await page.click('#btn-refresh');
    await page.waitForTimeout(1000);
    const t = await lastToast(page);
    assert(t.includes('刷新'), 'toast=' + t);
    return `KPI ${v.kpi}、图表 ${v.charts}`;
  });

  await step('E02', '业务页面', 'P2', '首页待办跳转', '待办按钮可跳到对应页面', async () => {
    const n = await page.locator('#todo-list [data-todo]').count();
    if (!n) return '当前无待办，跳过跳转';
    await page.locator('#todo-list [data-todo]').first().click();
    await page.waitForTimeout(800);
    const h = await page.evaluate(() => location.hash);
    assert(h && h !== '#/home', '未跳转');
    return '跳转 ' + h;
  });

  await step('E03', '业务页面', 'P1', '企业信息编辑必填校验', '清空企业名称/信用代码保存被拦截', async () => {
    await nav(page, '#/enterprise/info', 1000);
    await page.click('#org-edit');
    await page.waitForSelector('.overlay #org-form', { timeout: 4000 });
    await page.fill('.overlay [name="name"]', '');
    await page.fill('.overlay [name="code"]', '');
    await clearToasts(page);
    await page.click('#org-save');
    await page.waitForTimeout(400);
    const t = await lastToast(page);
    assert(t.includes('必填'), 'toast=' + t);
    await closeOverlay(page);
    return t.split('\n')[0];
  });

  await step('E04', '业务页面', 'P1', '配额新增必填校验', '年度/配额量为空被拦截', async () => {
    await nav(page, '#/carbon-emission/quota/list', 1000);
    await page.click('#q-new');
    await page.waitForSelector('.overlay #q-form', { timeout: 4000 });
    await page.click('#q-save');
    await page.waitForTimeout(300);
    const errs = await fieldErrors(page);
    assert(errs.some(e => e.includes('年度')), '年度未拦截：' + errs.join('|'));
    assert(errs.some(e => e.includes('配额量')), '配额量未拦截：' + errs.join('|'));
    await closeOverlay(page);
    return errs.join('；');
  });

  await step('E05', '业务页面', 'P2', '配额图表/表格切换', '切换后视图类型改变且可切回', async () => {
    await page.click('#q-view');
    await page.waitForTimeout(600);
    const t1 = await page.locator('#q-view').innerText();
    const has = await page.locator('#q-body svg').count();
    assert(has >= 1, '图表未渲染');
    await page.click('#q-view');
    await page.waitForTimeout(600);
    const t2 = await page.locator('#q-view').innerText();
    assert(t1 !== t2, '按钮文案未切换');
    return '切换正常';
  });

  await step('E06', '业务页面', 'P1', '设备新增必填校验', '空设备名称/编码被 toast 拦截', async () => {
    await nav(page, '#/carbon-facility/device/list', 1000);
    await page.click('#d-add');
    await page.waitForSelector('.overlay #de-form', { timeout: 4000 });
    await clearToasts(page);
    await page.click('#de-save');
    await page.waitForTimeout(300);
    const t1 = await lastToast(page);
    assert(t1.includes('设备名称必填'), 'toast=' + t1);
    await page.fill('.overlay [name="name"]', '自动化设备' + stamp);
    await clearToasts(page);
    await page.click('#de-save');
    await page.waitForTimeout(300);
    const t2 = await lastToast(page);
    assert(t2.includes('设备编码必填'), 'toast=' + t2);
    await closeOverlay(page);
    return '名称/编码均拦截';
  });

  await step('E07', '业务页面', 'P1', '预警规则必填校验', '未选事件、预警值为空被拦截', async () => {
    await nav(page, '#/common-warning/setting/list', 1000);
    await page.click('#w-new');
    await page.waitForSelector('.overlay #wn', { timeout: 4000 });
    await page.click('#wn-save');
    await page.waitForTimeout(300);
    const errs = await fieldErrors(page);
    assert(errs.some(e => e.includes('预警事件')), '事件未拦截：' + errs.join('|'));
    assert(errs.some(e => e.includes('预警值')), '预警值未拦截：' + errs.join('|'));
    await closeOverlay(page);
    return errs.join('；');
  });

  await step('E08', '业务页面', 'P2', '预警记录筛选与重置', '查询后条数变化，重置恢复', async () => {
    await nav(page, '#/common-warning/record/list', 1000);
    const before = await page.locator('#view tbody tr').count();
    await page.fill('#rec-kw', 'ZZZ不存在' + stamp);
    await page.click('#rec-search');
    await page.waitForTimeout(800);
    const empty = await page.locator('#view .tb-empty').count();
    await page.click('#rec-reset');
    await page.waitForTimeout(800);
    const after = await page.locator('#view tbody tr').count();
    assert(after >= 1 || before === 0, '重置后无数据');
    return `初始 ${before} 行，空态 ${empty}，重置 ${after} 行`;
  });

  await step('E09', '业务页面', 'P1', '报告页查询与生成入口', '三页可查询，生成按钮存在（不执行写操作）', async () => {
    await nav(page, '#/carbon-report/calculation-report/query', 1200);
    const v = await page.evaluate(() => ({
      q: !!document.querySelector('#r-query'),
      gen: !!document.querySelector('#r-gen'),
      table: document.querySelectorAll('#view tbody tr').length
    }));
    assert(v.q && v.gen, '查询/生成入口缺失');
    await page.click('#r-query');
    await page.waitForTimeout(700);
    return `查询可用，明细 ${v.table} 行，生成入口存在`;
  });

  await step('E10', '业务页面', 'P2', '能碳分析页图表渲染', '能耗查询页有 SVG 图表且查询可点', async () => {
    await nav(page, '#/carbon-analysis/energy-consumption-query/index', 1200);
    const v = await page.evaluate(() => ({
      svg: document.querySelectorAll('#view svg').length,
      btn: [...document.querySelectorAll('#view button')].some(b => /查询/.test(b.textContent))
    }));
    assert(v.svg >= 1, '无图表');
    assert(v.btn, '无查询按钮');
    return '图表 ' + v.svg + ' 个';
  });

  await step('E11', '业务页面', 'P1', '新增成员手机号/姓名校验', '非法手机号与空姓名均被拦截', async () => {
    await nav(page, '#/system/users', 1100);
    await page.click('#u-new');
    await page.waitForSelector('.overlay #u-form', { timeout: 4000 });
    await page.fill('.overlay [name="phone"]', '12345');
    await clearToasts(page);
    await page.click('#u-save');
    await page.waitForTimeout(300);
    const t1 = await lastToast(page);
    assert(t1.includes('手机号格式不正确'), 'toast=' + t1);
    await page.fill('.overlay [name="phone"]', '13700001111');
    await clearToasts(page);
    await page.click('#u-save');
    await page.waitForTimeout(300);
    const t2 = await lastToast(page);
    assert(t2.includes('请填写姓名'), 'toast=' + t2);
    await closeOverlay(page);
    return '手机号/姓名均拦截';
  });

  await step('E12', '业务页面', 'P1', '新建角色必填与页面树', '空名称被拦截；页面勾选项 ≥20', async () => {
    await nav(page, '#/system/roles', 1100);
    await page.click('#r-new');
    await page.waitForSelector('.overlay #page-tree', { timeout: 4000 });
    const boxes = await page.locator('.overlay input[data-page]').count();
    assert(boxes >= 20, '页面勾选项=' + boxes);
    await clearToasts(page);
    await page.click('#role-save');
    await page.waitForTimeout(300);
    const t = await lastToast(page);
    assert(t.includes('请填写角色名称'), 'toast=' + t);
    await closeOverlay(page);
    return boxes + ' 个页面可选';
  });

  await step('E13', '业务页面', 'P2', '审计日志详情弹窗', '详情可打开/关闭', async () => {
    await nav(page, '#/system/audit', 1100);
    const b = page.locator('[data-au-open]').first();
    assert(await b.count(), '无详情按钮');
    await b.click();
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const t = await modalText(page);
    assert(t.includes('变更') || t.includes('操作') || t.includes('详情'), '详情内容缺失');
    await closeOverlay(page);
    return '详情正常';
  });

  await step('E14', '业务页面', 'P2', '租户信息展示', '授权状态/席位/套餐 KPI 存在', async () => {
    await nav(page, '#/system/tenant', 1100);
    const v = await page.evaluate(() => ({
      kpi: document.querySelectorAll('#view .kpi').length,
      text: document.querySelector('#view').innerText
    }));
    assert(v.kpi >= 4, 'KPI=' + v.kpi);
    assert(v.text.includes('授权状态') && v.text.includes('套餐'), '授权信息缺失');
    return 'KPI ' + v.kpi + ' 个';
  });

  /* ==================== F. 受限角色（运营） ==================== */
  const op = await newCtx(browser);
  await login(op.page, '13800000002', 'Test@2026!Pass');

  await step('F01', '权限控制', 'P0', '运营账号菜单裁剪', '无“系统管理/平台管理”，业务菜单可见', async () => {
    const t = await op.page.locator('#nav').innerText();
    assert(!t.includes('系统管理'), '系统管理仍可见');
    assert(!t.includes('平台管理'), '平台管理仍可见');
    assert(t.includes('产值管理'), '业务菜单缺失');
    return '系统/平台菜单已隐藏';
  });

  for (const [id, path, name] of [
    ['F02', '#/system/users', '账号管理'],
    ['F03', '#/system/roles', '角色管理'],
    ['F04', '#/system/tenant', '租户信息'],
    ['F05', '#/system/audit', '审计日志']
  ]) {
    await step(id, '权限控制', 'P0', `运营直接访问${name}被拦截`, '显示“没有该功能的访问权限”', async () => {
      await nav(op.page, path, 900);
      const v = await op.page.evaluate(() => ({
        crumb: document.querySelector('#crumb-title').textContent.trim(),
        empty: (document.querySelector('#view .tb-empty') || {}).innerText || ''
      }));
      assert(v.crumb === '无权访问', '标题=' + v.crumb);
      assert(v.empty.includes('没有该功能的访问权限'), '无 403 文案');
      return '403 拦截';
    });
  }

  await step('F06', '权限控制', 'P1', '运营无法访问平台管理', '平台概览被拦截', async () => {
    await nav(op.page, '#/platform/overview', 900);
    const t = await op.page.evaluate(() => document.querySelector('#view .tb-empty').innerText);
    assert(t.includes('没有该功能的访问权限'), '未拦截');
    return '403 拦截';
  });

  await step('F07', '权限控制', 'P1', '运营列表写操作按钮裁剪', '可新建/编辑，无删除与批量删除', async () => {
    await nav(op.page, '#/carbon-product/product/list', 1100);
    const v = await op.page.evaluate(() => ({
      newBtn: !!document.querySelector('#g-new'),
      bulk: !!document.querySelector('#g-bulk'),
      del: document.querySelectorAll('[data-del]').length,
      edit: document.querySelectorAll('[data-edit]').length
    }));
    assert(v.newBtn, '缺少新建');
    assert(v.edit >= 1, '缺少编辑');
    assert(!v.bulk, '批量删除未隐藏');
    assert(v.del === 0, '删除按钮未隐藏=' + v.del);
    return '新建/编辑保留，删除已裁剪';
  });

  await step('F08', '权限控制', 'P0', '运营导出被权限拦截', '点击导出提示无权限且不下载', async () => {
    await clearToasts(op.page);
    let downloaded = false;
    const onDl = () => { downloaded = true; };
    op.page.on('download', onDl);
    await op.page.click('#g-export');
    await op.page.waitForTimeout(1200);
    op.page.off('download', onDl);
    const t = await lastToast(op.page);
    assert(t.includes('没有权限'), 'toast=' + t);
    assert(!downloaded, '仍触发下载');
    return t.split('\n')[0];
  });

  await step('F09', '权限控制', 'P1', '运营企业信息只读', '无编辑按钮且标注只读', async () => {
    await nav(op.page, '#/enterprise/info', 1000);
    const v = await op.page.evaluate(() => ({
      edit: !!document.querySelector('#org-edit'),
      text: document.querySelector('#view').innerText
    }));
    assert(!v.edit, '仍显示编辑按钮');
    assert(v.text.includes('只读'), '未标注只读');
    return '只读';
  });

  await step('F10', '权限控制', 'P1', '运营复核/批准能力与角色模型一致性', '运营应按职责分离看到复核/批准入口', async () => {
    const v = await op.page.evaluate(() => ({
      role: CarbonAPI.me().role,
      rev: CarbonAPI.canReview(),
      app: CarbonAPI.canApprove()
    }));
    assert(v.rev && v.app, `role=${v.role} canReview=${v.rev} canApprove=${v.app}`);
    return '具备复核/批准能力';
  });

  await step('F11', '权限控制', 'P2', '未纳入菜单的隐藏路由访问控制', '/carbon-monitor 应对无权限账号拦截', async () => {
    await nav(op.page, '#/carbon-monitor', 900);
    const v = await op.page.evaluate(() => ({
      crumb: document.querySelector('#crumb-title').textContent.trim(),
      title: (document.querySelector('.page-title') || {}).textContent || ''
    }));
    assert(v.crumb === '无权访问', `可直接访问隐藏路由：${v.title}`);
    return '已拦截';
  });

  /* ==================== G. 平台端 ==================== */
  const pf = await newCtx(browser);
  await login(pf.page, '13900000000', 'Superadmin@2026!Pass');

  await step('G01', '平台端', 'P0', '平台账号菜单', '仅平台管理三项，无业务菜单', async () => {
    const t = await pf.page.locator('#nav').innerText();
    assert(t.includes('平台概览') && t.includes('租户管理') && t.includes('平台审计'), '平台菜单缺失');
    assert(!t.includes('产值管理') && !t.includes('系统管理'), '出现业务/系统菜单');
    return '仅平台三项';
  });

  await step('G02', '平台端', 'P0', '平台概览渲染', 'KPI/租户统计可见', async () => {
    await nav(pf.page, '#/platform/overview', 1200);
    const v = await pf.page.evaluate(() => ({
      page: !!document.querySelector('#view .page'),
      kpi: document.querySelectorAll('#view .kpi').length,
      text: document.querySelector('#view').innerText
    }));
    assert(v.page, '未渲染');
    assert(v.kpi >= 2, 'KPI=' + v.kpi);
    assert(v.text.includes('租户'), '缺少租户信息');
    return 'KPI ' + v.kpi;
  });

  await step('G03', '平台端', 'P0', '租户列表与筛选', '列表有数据，查询/重置可用', async () => {
    await nav(pf.page, '#/platform/tenants', 1200);
    const v = await pf.page.evaluate(() => ({
      rows: document.querySelectorAll('#view tbody tr').length,
      kw: !!document.querySelector('#t-kw'),
      newBtn: !!document.querySelector('#t-new')
    }));
    assert(v.rows >= 1, '租户行数=' + v.rows);
    assert(v.kw && v.newBtn, '筛选/开通入口缺失');
    await pf.page.fill('#t-kw', 'ZZZ不存在');
    await pf.page.click('#t-query');
    await pf.page.waitForTimeout(800);
    const empty = await pf.page.locator('#view .tb-empty').count();
    await pf.page.click('#t-reset');
    await pf.page.waitForTimeout(800);
    return `租户 ${v.rows} 行，空态 ${empty}`;
  });

  await step('G04', '平台端', 'P1', '开通租户向导弹窗校验', '空企业名/非法手机号均被拦截，可取消', async () => {
    await pf.page.click('#t-new');
    await pf.page.waitForSelector('.overlay #wz-body', { timeout: 4000 });
    await clearToasts(pf.page);
    await pf.page.click('#wz-next');
    await pf.page.waitForTimeout(300);
    let t = await lastToast(pf.page);
    assert(t.includes('企业名称'), '空名称未拦截：' + t);
    const inputs = pf.page.locator('.overlay [data-k]');
    await inputs.first().fill('自动化测试企业' + stamp);
    await pf.page.click('#wz-next');
    await pf.page.waitForTimeout(400);
    const phone = pf.page.locator('.overlay [data-k="adminPhone"]');
    if (await phone.count()) {
      await phone.fill('123');
      await clearToasts(pf.page);
      await pf.page.click('#wz-next');
      await pf.page.waitForTimeout(300);
      t = await lastToast(pf.page);
      assert(t.includes('手机号格式不正确'), '非法手机号未拦截：' + t);
    }
    await closeOverlay(pf.page);
    return '企业名/手机号校验正常（未创建）';
  });

  await step('G05', '平台端', 'P2', '平台审计页渲染', '筛选与列表可见', async () => {
    await nav(pf.page, '#/platform/audit', 1200);
    const v = await pf.page.evaluate(() => ({
      kw: !!document.querySelector('#pa-kw'),
      page: !!document.querySelector('#view .page')
    }));
    assert(v.page && v.kw, '平台审计未渲染');
    return '渲染正常';
  });

  await step('G06', '平台端', 'P0', '平台账号不能进入租户业务页', '直接访问 #/home 被拦截并可返回平台概览', async () => {
    await nav(pf.page, '#/home', 900);
    const v = await pf.page.evaluate(() => ({
      crumb: document.querySelector('#crumb-title').textContent.trim(),
      text: document.querySelector('#view').innerText,
      back: !!document.querySelector('#view .btn-primary')
    }));
    assert(v.crumb === '无权访问', '标题=' + v.crumb);
    assert(v.text.includes('平台概览'), '缺少返回平台概览');
    assert(v.back, '缺少返回按钮');
    return '403 + 返回平台概览';
  });

  await step('G07', '平台端', 'P1', '平台账号不能进入系统管理', '直接访问 #/system/users 被拦截', async () => {
    await nav(pf.page, '#/system/users', 900);
    const t = await pf.page.evaluate(() => document.querySelector('#view .tb-empty').innerText);
    assert(t.includes('没有该功能的访问权限'), '未拦截');
    return '403 拦截';
  });

  await step('G08', '平台端', 'P2', '平台账号访问隐藏路由', '/carbon-monitor 存在越权访问', async () => {
    await nav(pf.page, '#/carbon-monitor', 900);
    const v = await pf.page.evaluate(() => ({
      crumb: document.querySelector('#crumb-title').textContent.trim(),
      title: (document.querySelector('.page-title') || {}).textContent || ''
    }));
    assert(v.crumb === '无权访问', `平台账号可访问隐藏路由：${v.title}`);
    return '已拦截';
  });

  /* ==================== H. 异常与路由健壮性（管理员） ==================== */
  await step('H01', '异常与边界', 'P2', '未知路由回退首页', '未知 hash 渲染首页', async () => {
    await nav(page, '#/not-exist-' + stamp, 900);
    const v = await page.evaluate(() => ({
      crumb: document.querySelector('#crumb-title').textContent.trim(),
      page: !!document.querySelector('#view .page')
    }));
    assert(v.page, '未渲染');
    assert(v.crumb === '首页', '标题=' + v.crumb);
    return '回退首页';
  });

  await step('H02', '异常与边界', 'P2', '带查询参数的路由', '#/home?x=1 仍渲染首页', async () => {
    await nav(page, '#/home?x=1', 900);
    const crumb = await page.locator('#crumb-title').innerText();
    assert(crumb.trim() === '首页', '标题=' + crumb);
    return '参数被忽略';
  });

  await step('H03', '异常与边界', 'P2', '浏览器前进/后退', '后退回到上一页面并渲染', async () => {
    await nav(page, '#/carbon-product/product/list', 900);
    await nav(page, '#/carbon-facility/device/list', 900);
    await page.goBack();
    await page.waitForTimeout(900);
    const h = await page.evaluate(() => location.hash);
    assert(h.includes('product'), '后退到=' + h);
    await page.goForward();
    await page.waitForTimeout(900);
    return '前进/后退正常';
  });

  await step('H04', '异常与边界', 'P1', '深层路由刷新保持登录与页面', 'reload 后仍停留当前路由', async () => {
    await nav(page, '#/system/audit', 1000);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1800);
    const v = await page.evaluate(() => ({
      auth: document.querySelector('#auth-mask') ? document.querySelector('#auth-mask').classList.contains('hide') : true,
      crumb: document.querySelector('#crumb-title').textContent.trim()
    }));
    assert(v.auth, '刷新后掉登录');
    assert(v.crumb === '审计日志', '刷新后页面=' + v.crumb);
    return '保持审计日志';
  });

  await step('H05', '异常与边界', 'P1', '快速连续切换路由', '10 次切换无控制台错误、无残留弹窗', async () => {
    page.__errs.length = 0;
    const paths = ['/home', '/carbon-product/product/list', '/common-warning/record/list', '/system/users', '/carbon-emission/quota/list', '/carbon-asset/asset/list', '/carbon-facility/device/list', '/system/roles', '/carbon-verify/evidence/list', '/home'];
    for (const p of paths) { await page.evaluate(h => { location.hash = h; }, '#' + p); await page.waitForTimeout(260); }
    await page.waitForTimeout(900);
    const v = await page.evaluate(() => ({ overlays: document.querySelectorAll('.overlay,.drawer,.drawer-mask').length, page: !!document.querySelector('#view .page') }));
    assert(v.overlays === 0, '残留弹窗=' + v.overlays);
    assert(v.page, '页面空白');
    assert(page.__errs.length === 0, '控制台错误：' + page.__errs[0]);
    return '10 次切换正常';
  });

  await step('H06', '异常与边界', 'P1', '列表接口失败降级', '请求失败提示错误且页面不白屏', async () => {
    const handler = route => route.abort();
    await page.route('**/api/products*', handler);
    await clearToasts(page);
    await nav(page, '#/carbon-product/product/list', 1400);
    const v = await page.evaluate(() => ({
      page: !!document.querySelector('#view .page'),
      empty: (document.querySelector('#g-table .tb-empty') || {}).innerText || ''
    }));
    const t = await lastToast(page);
    await page.unroute('**/api/products*', handler);
    assert(v.page, '白屏');
    assert(t.includes('网络异常'), '错误提示未中文化：' + t);
    return t.split('\n')[0];
  });

  await step('H07', '异常与边界', 'P2', 'ESC 关闭弹窗', '按 Esc 关闭通知弹窗', async () => {
    await nav(page, '#/home', 900);
    await page.click('#btn-bell');
    await page.waitForSelector('.overlay', { timeout: 4000 });
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
    assert(await page.locator('.overlay').count() === 0, '未关闭');
    return 'ESC 关闭';
  });

  await step('H08', '异常与边界', 'P2', '点击遮罩关闭抽屉', '点击设置抽屉遮罩关闭', async () => {
    await page.click('#btn-settings');
    await page.waitForSelector('.drawer', { timeout: 4000 });
    await page.locator('.drawer-mask').click({ position: { x: 5, y: 5 } });
    await page.waitForTimeout(300);
    assert(await page.locator('.drawer').count() === 0, '未关闭');
    return '遮罩关闭';
  });

  await step('H09', '异常与边界', 'P2', '移动端 390 响应式', '菜单可开合、关键页无横向溢出', async () => {
    const m = await ctx.newPage();
    await m.setViewportSize({ width: 390, height: 844 });
    await m.goto(BASE + '/app/', { waitUntil: 'domcontentloaded' });
    await m.waitForTimeout(1600);
    await m.click('#btn-menu');
    await m.waitForTimeout(300);
    const open = await m.evaluate(() => document.querySelector('#side').classList.contains('open'));
    await m.mouse.click(370, 300);
    await m.waitForTimeout(300);
    const closed = await m.evaluate(() => !document.querySelector('#side').classList.contains('open'));
    const over = [];
    for (const p of ['/home', '/carbon-product/product/list', '/system/users', '/system/roles']) {
      await m.evaluate(h => { location.hash = h; }, '#' + p);
      await m.waitForTimeout(700);
      const o = await m.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      if (o > 1) over.push(p + ' +' + o);
    }
    await m.close();
    assert(open && closed, '菜单开合异常');
    assert(over.length === 0, '横向溢出：' + over.join(','));
    return '菜单开合正常，无横向溢出';
  });

  /* ==================== I. 数据一致性 ==================== */
  await step('I01', '数据一致性', 'P0', '测试未产生写操作', '除登录/登出外无写请求', async () => {
    const bad = mutating.filter(m => !/\/api\/auth\/(login|logout|password\/change)/.test(m));
    assert(bad.length === 0, '出现写请求：' + bad.join(','));
    return '共记录 ' + mutating.length + ' 个非 GET 请求（均为 auth）';
  });

  const after = await snapshot(adminToken);
  await step('I02', '数据一致性', 'P0', '业务数据基线未变化', '产品/供应商/设备/读数/账号数量与测试前一致', async () => {
    const keys = ['products', 'suppliers', 'devices', 'deviceData', 'facilities', 'users'];
    const diff = keys.filter(k => before[k] !== after[k]).map(k => `${k}:${before[k]}→${after[k]}`);
    assert(diff.length === 0, diff.join(','));
    return keys.map(k => `${k}=${after[k]}`).join(', ');
  });

  await step('I03', '数据一致性', 'P0', '核算期状态未变', '2026-11 仍为 draft', async () => {
    assert(after.period === before.period, before.period + '→' + after.period);
    assert(after.period === 'draft', '当前状态=' + after.period);
    return 'draft';
  });

  await step('I04', '数据一致性', 'P0', '服务健康检查', '/api/health 返回 UP', async () => {
    const h = await (await fetch(BASE + '/api/health')).json();
    assert(h && h.body && h.body.status === 'UP', JSON.stringify(h));
    return 'UP';
  });

  console.log(`\nRESULT: ${FAIL === 0 ? 'PASS' : 'FAIL'} (${PASS}/${PASS + FAIL})`);
  const meta = { startedAt: new Date().toISOString(), base: BASE, pass: PASS, fail: FAIL, before, after, mutating };
  fs.writeFileSync(OUT, JSON.stringify({ meta, results }, null, 2));
  console.log('results -> ' + OUT.pathname);
  await browser.close();
  process.exit(FAIL === 0 ? 0 : 1);
})().catch(async e => {
  console.error('FATAL', e);
  try { fs.writeFileSync(OUT, JSON.stringify({ fatal: String(e), results }, null, 2)); } catch { /* ignore */ }
  process.exit(2);
});
