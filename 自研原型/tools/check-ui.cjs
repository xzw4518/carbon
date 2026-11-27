#!/usr/bin/env node
/* L1 UI 门禁（可复跑）：真实服务登录 → 全部路由渲染 → 控制台错误 → 列表数据 → 移动端　
   → 无障碍基础项（无三方依赖）→ 键盘可达 → Firefox / WebKit 尝试。
   前置：后端已启动（默认 http://localhost:8088）。
   用法：node tools/check-ui.cjs
   可用环境变量覆盖：APP_URL / APP_USER / APP_PASS / CHROME_PATH / A11Y_STRICT=1
   依赖：playwright-core + 本机 Chrome。缺失时以退出码 2 结束（SKIP，不冒充 PASS）。

   P2-6 说明：axe-core / Lighthouse 需要联网安装，本仓库离线环境没有；因此无障碍检查用
   **纯 DOM 断言**实现（img alt、按钮可访问名、表单 label、document.title），
   并把 Firefox / WebKit 作为"尽力尝试"：浏览器未安装时记 [SKIP] 并在 RESULT 里报 skipped 数，
   绝不写成 PASS。A11Y_STRICT=1 时 label 缺失也从 WARN 升级为 FAIL。 */
'use strict';
const fs = require('fs');

const BASE = (process.env.APP_URL || 'http://localhost:8088/app/').replace(/\/?$/, '/');
const USER = process.env.APP_USER || '13800000001';
const PASS = process.env.APP_PASS || 'Admin@2026!Pass';
const SKIP = new Set(['/external/portal', '/group/overview', '/platform/overview', '/platform/tenants', '/platform/audit']);
const A11Y_STRICT = process.env.A11Y_STRICT === '1';
/** 需要登录后才能访问的路由（平台侧 / 外部门户除外）。 */
const SKIP_REASON = {
  '/external/portal': '外部供应商门户，需独立免登录会话',
  '/group/overview': '集团汇总为特权页面，默认管理员未授予',
  '/platform/overview': '平台运营侧，需 platform_admin',
  '/platform/tenants': '平台运营侧，需 platform_admin',
  '/platform/audit': '平台运营侧，需 platform_admin',
};

const results = [];
const skips = [];
const ok = (id, name, detail) => results.push({ id, name, pass: true, detail });
const bad = (id, name, detail) => results.push({ id, name, pass: false, detail });
const warn = (id, name, detail) => results.push({ id, name, pass: true, warn: true, detail });
const skip = (id, name, detail) => skips.push({ id, name, detail });

let chromium = null;
let firefox = null;
let webkit = null;
try {
  const pw = require('playwright-core');
  chromium = pw.chromium;
  firefox = pw.firefox;
  webkit = pw.webkit;
} catch (e) {
  try {
    const pw = require('playwright');
    chromium = pw.chromium;
    firefox = pw.firefox;
    webkit = pw.webkit;
  } catch (e2) {
    console.log('[SKIP] U00 Playwright 未安装');
    console.log('\n安装：npm i -D playwright-core    # 项目根目录执行');
    console.log('或设置 CHROME_PATH 指向本机 Chrome。');
    process.exit(2);
  }
}

const CHROME = [
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium-browser',
].filter(Boolean).find((p) => { try { return fs.existsSync(p); } catch (e) { return false; } });

const format = () => {
  const width = Math.max(...results.map((r) => r.name.length), 10);
  const f = results.filter((r) => !r.pass).length;
  const w = results.filter((r) => r.warn).length;
  const lines = results.map((r) => {
    const tag = !r.pass ? 'FAIL' : r.warn ? 'WARN' : 'PASS';
    return `[${tag}] ${r.id} ${r.name.padEnd(width)}  ${r.detail}`;
  });
  const skipLines = skips.map((s) => `[SKIP] ${s.id} ${s.name.padEnd(width)}  ${s.detail}`);
  return [...lines, ...skipLines].join('\n')
    + `\n\nRESULT: ${f === 0 ? 'PASS' : 'FAIL'} (${results.length - f}/${results.length}`
    + `${w ? `, warn=${w}` : ''}${skips.length ? `, skipped=${skips.length}` : ''})`;
};

/* 登录并返回 page（供多个浏览器引擎复用同一套断言） */
async function runSuite(browser, tag, suites) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 950 }, bypassCSP: true });
  const page = await ctx.newPage();
  let errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));

  try {
    const resp = await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 15000 });
    if (!resp || resp.status() !== 200) throw new Error('HTTP ' + (resp && resp.status()));

    const loginForm = await page.$('#auth-phone');
    if (loginForm) {
      const prefill = await page.evaluate(() => ({
        phone: document.querySelector('#auth-phone').value,
        pwd: document.querySelector('#auth-pwd').value,
        tip: !!document.querySelector('.auth-tip'),
      }));
      if (tag === 'chromium') {
        if (prefill.phone || prefill.pwd) bad('U00', '登录页预填', '手机号/密码被预填');
        else if (prefill.tip) bad('U00', '登录页预填', '仍存在演示账号提示');
        else ok('U00', '登录页预填', '无预填、无演示提示');
      }
      await page.fill('#auth-phone', USER);
      await page.fill('#auth-pwd', PASS);
      // 密码登录已强制图形验证码：从页面 SVG 里解析字符
      await page.waitForFunction(() => {
        const img = document.querySelector('#auth-cap-img');
        return img && img.src && img.src.indexOf('data:image') === 0;
      }, null, { timeout: 8000 }).catch(() => {});
      const capCode = await page.evaluate(() => {
        const img = document.querySelector('#auth-cap-img');
        if (!img || !img.src || img.src.indexOf('data:image') !== 0) return '';
        try {
          const svg = atob(img.src.split(',')[1]);
          return (svg.match(/<text[^>]*>[^<]<\/text>/g) || [])
            .map(t => t.replace(/.*>([^<])<\/text>/, '$1')).join('');
        } catch (e) {
          return '';
        }
      });
      if (capCode) await page.fill('#auth-cap', capCode);
      await page.click('#auth-login');
    }
    await page.waitForFunction(() => {
      const m = document.querySelector('#auth-mask');
      return !m || m.classList.contains('hide');
    }, null, { timeout: 15000 });
    const me = await page.evaluate(() => (window.CarbonAPI && CarbonAPI.me && CarbonAPI.me()) || null);
    if (!me) throw new Error('登录后未取到用户信息');
  } catch (e) {
    if (tag === 'chromium') {
      bad('U01', '登录', e.message.slice(0, 100));
      await ctx.close();
      return false;
    }
    throw e;
  }
  await suites(page, ctx, tag);
  await ctx.close();
  return true;
}

(async () => {
  let browser;
  try {
    browser = await chromium.launch(CHROME ? { executablePath: CHROME, headless: true } : { headless: true });
  } catch (e) {
    console.log('[SKIP] U00 无法启动浏览器：' + e.message);
    process.exit(2);
  }

  const suites = async (page, ctx) => {
    await page.waitForFunction(() => Object.keys((window.UI && UI.routes) || {}).length >= 30, null, { timeout: 15000 });
    const routes = await page.evaluate(() => Object.keys(UI.routes || {}));
    const testable = routes.filter((r) => !SKIP.has(r));

    const failed = [];
    const navErrors = [];
    let errors = [];
    page.removeAllListeners('console');
    page.removeAllListeners('pageerror');
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));

    for (const r of testable) {
      errors = [];
      await page.evaluate((h) => { location.hash = h; }, '#' + r);
      await page.waitForTimeout(420);
      const st = await page.evaluate(() => {
        const v = document.querySelector('#view');
        const text = v ? v.innerText : '';
        return {
          page: !!(v && v.querySelector('.page')),
          nan: /NaN|\[object Object\]/.test(text),
        };
      });
      if (!st.page || st.nan) failed.push(r + (st.nan ? '(NaN)' : '(未渲染)'));
      if (errors.length) navErrors.push(r + ': ' + errors[0].slice(0, 60));
    }
    failed.length ? bad('U01', '路由渲染', failed.slice(0, 6).join(', ')) : ok('U01', '路由渲染', testable.length + '/' + testable.length);
    navErrors.length ? bad('U02', '控制台错误', navErrors.slice(0, 3).join(' | ')) : ok('U02', '控制台错误', '0 条');

    const dataPages = ['/carbon-product/product/list', '/carbon-verify/evidence/list', '/system/users', '/carbon-facility/device-data/list', '/system/audit'];
    const empty = [];
    for (const r of dataPages) {
      await page.evaluate((h) => { location.hash = h; }, '#' + r);
      await page.waitForTimeout(500);
      const n = await page.evaluate(() => document.querySelectorAll('#view table.tb tbody tr').length);
      if (!n) empty.push(r);
    }
    empty.length ? bad('U03', '列表数据', '无数据行: ' + empty.join(', ')) : ok('U03', '列表数据', dataPages.length + ' 个列表有数据');

    /* ---------- U05 无障碍基础项（纯 DOM，无三方库） ---------- */
    const a11yPages = ['/home', '/carbon-product/product/list', '/system/users', '/system/roles', '/system/audit',
      '/carbon-computation/calculation-complete/list', '/carbon-verify/evidence/list'];
    const a11y = { imgNoAlt: [], btnNoName: [], inputNoLabel: [], noTitle: [] };
    for (const r of a11yPages) {
      await page.evaluate((h) => { location.hash = h; }, '#' + r);
      await page.waitForTimeout(380);
      const one = await page.evaluate(() => {
        const out = { imgNoAlt: 0, btnNoName: 0, inputNoLabel: 0, noTitle: '' };
        if (!document.title || !document.title.trim()) out.noTitle = location.hash;
        document.querySelectorAll('#view img, #app img').forEach((img) => {
          if (!img.hasAttribute('alt')) out.imgNoAlt += 1;
        });
        const nameOf = (el) => (el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent || '').trim();
        document.querySelectorAll('#view button, #app button, #view [role="button"]').forEach((b) => {
          if (!nameOf(b)) out.btnNoName += 1;
        });
        document.querySelectorAll('#view input, #view select, #view textarea').forEach((el) => {
          const type = (el.getAttribute('type') || '').toLowerCase();
          if (type === 'hidden') return;
          const id = el.getAttribute('id');
          const labelled = (id && document.querySelector(`label[for="${id}"]`))
            || el.closest('label')
            || el.getAttribute('aria-label')
            || el.getAttribute('aria-labelledby');
          if (!labelled) out.inputNoLabel += 1;
        });
        return out;
      });
      if (one.noTitle) a11y.noTitle.push(one.noTitle);
      if (one.imgNoAlt) a11y.imgNoAlt.push(r + ':' + one.imgNoAlt);
      if (one.btnNoName) a11y.btnNoName.push(r + ':' + one.btnNoName);
      if (one.inputNoLabel) a11y.inputNoLabel.push(r + ':' + one.inputNoLabel);
    }
    const noAlt = a11y.imgNoAlt.reduce((n, s) => n + Number(s.split(':')[1]), 0);
    const noBtn = a11y.btnNoName.reduce((n, s) => n + Number(s.split(':')[1]), 0);
    const noLabel = a11y.inputNoLabel.reduce((n, s) => n + Number(s.split(':')[1]), 0);
    noAlt ? bad('U05', 'A11Y img alt', a11y.imgNoAlt.join(', '))
      : ok('U05', 'A11Y img alt', a11yPages.length + ' 页图片均有 alt');
    noBtn ? bad('U05b', 'A11Y 按钮可访问名', a11y.btnNoName.join(', '))
      : ok('U05b', 'A11Y 按钮可访问名', a11yPages.length + ' 页按钮均有文本/aria-label');
    if (noLabel) {
      (A11Y_STRICT ? bad : warn)('U05c', 'A11Y 表单 label', a11y.inputNoLabel.join(', ') + '（A11Y_STRICT=1 时阻断）');
    } else ok('U05c', 'A11Y 表单 label', a11yPages.length + ' 页控件均有 label/aria-label');
    a11y.noTitle.length ? bad('U05d', 'A11Y document.title', '空标题: ' + a11y.noTitle.join(', '))
      : ok('U05d', 'A11Y document.title', '标题非空');

    /* ---------- U06 键盘可达（Tab 能推进焦点） ---------- */
    await page.evaluate(() => { location.hash = '#/home'; });
    await page.waitForTimeout(400);
    const focusable = await page.evaluate(() => document.querySelectorAll('#view a[href], #view button, #view input, #view select, #view textarea, #view [tabindex]:not([tabindex="-1"])').length);
    let tabMoved = 0;
    if (focusable > 0) {
      await page.evaluate(() => document.body.focus());
      const seen = new Set();
      for (let i = 0; i < 8; i += 1) {
        await page.keyboard.press('Tab');
        const sig = await page.evaluate(() => {
          const a = document.activeElement;
          if (!a || a === document.body) return '';
          return (a.tagName || '') + '|' + (a.id || a.getAttribute('data-path') || a.textContent || '').slice(0, 18);
        });
        if (sig) { seen.add(sig); tabMoved = seen.size; }
      }
    }
    if (focusable === 0) skip('U06', '键盘可达', '首页无 focusable 元素（纯展示页，无法判定）');
    else if (tabMoved >= 2) ok('U06', '键盘可达', `Tab 推进 ${tabMoved} 个焦点（可聚焦 ${focusable} 个）`);
    else bad('U06', '键盘可达', `Tab 只推进 ${tabMoved} 个焦点（可聚焦 ${focusable} 个）`);

    /* ---------- U04 移动端 ---------- */
    const mobile = await ctx.newPage();
    await mobile.setViewportSize({ width: 390, height: 844 });
    await mobile.goto(BASE, { waitUntil: 'domcontentloaded' });
    await mobile.waitForTimeout(1200);
    const over = [];
    for (const r of ['/home', '/carbon-product/product/list', '/common-warning/record/list', '/carbon-computation/calculation-complete/list', '/system/audit', '/system/roles']) {
      await mobile.evaluate((h) => { location.hash = h; }, '#' + r);
      await mobile.waitForTimeout(500);
      const o = await mobile.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      if (o > 1) over.push(r + ' +' + o + 'px');
    }
    over.length ? bad('U04', '移动端 390', over.join(' | ')) : ok('U04', '移动端 390', '6 页无横向溢出');
    await mobile.close();

    /* 显式打印被跳过的路由（P2-6：不允许"悄悄少测"） */
    const skippedInRoutes = routes.filter((r) => SKIP.has(r));
    skippedInRoutes.forEach((r) => skip('U07', '路由跳过', r + ' — ' + (SKIP_REASON[r] || '按需登录/权限')));
    const notRegistered = [...SKIP].filter((r) => !routes.includes(r));
    notRegistered.forEach((r) => skip('U07', '路由未注册', r + ' — 前端未声明该路由，无需测试'));
    if (testable.length + skippedInRoutes.length === routes.length) {
      ok('U07', '路由覆盖', `${testable.length} 测 + ${skippedInRoutes.length} 跳过 = ${routes.length} 全部路由`);
    } else {
      bad('U07', '路由覆盖', `路由数不匹配：测 ${testable.length} + 跳过 ${skippedInRoutes.length} ≠ ${routes.length}`);
    }
  };

  const launched = await runSuite(browser, 'chromium', suites);
  await browser.close();
  if (!launched) {
    console.log(format());
    process.exit(1);
  }

  /* ---------- U08 / U09 其它引擎（未安装则 SKIP，不冒充 PASS） ---------- */
  for (const [id, name, engine] of [['U08', 'Firefox 引擎', firefox], ['U09', 'WebKit 引擎', webkit]]) {
    if (!engine) { skip(id, name, 'playwright-core 未导出该引擎'); continue; }
    let b = null;
    try {
      b = await engine.launch({ headless: true });
    } catch (e) {
      skip(id, name, '浏览器未安装（' + String(e.message).split('\n')[0].slice(0, 70) + '）');
      continue;
    }
    try {
      const smoke = async (page) => {
        const st = await page.evaluate(() => {
          const v = document.querySelector('#view');
          return { page: !!(v && v.querySelector('.page')), text: (v ? v.innerText : '').slice(0, 40) };
        });
        st.page ? ok(id, name, '登录 + 首页渲染 OK') : bad(id, name, '首页未渲染');
      };
      await runSuite(b, id.toLowerCase(), smoke);
    } catch (e) {
      bad(id, name, String(e.message).split('\n')[0].slice(0, 90));
    } finally {
      await b.close();
    }
  }

  console.log(format());
  const failedCount = results.filter((r) => !r.pass).length;
  process.exit(failedCount === 0 ? 0 : 1);
})();
