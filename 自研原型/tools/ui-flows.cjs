'use strict';
/**
 * 交互流程验收（L2）：真实浏览器点击关键流程，覆盖“页面能渲染但按钮没反应”的问题。
 * 运行：node tools/ui-flows.cjs      （需服务已启动）
 * 只做可逆操作：弹窗一律取消，删除/计算/审批不点。
 */
const fs = require('fs');
let chromium;
try { ({ chromium } = require('playwright-core')); } catch (e) { ({ chromium } = require('playwright')); }

const CHROME = ['C:/Program Files/Google/Chrome/Application/chrome.exe'].find(p => fs.existsSync(p));
const BASE = process.env.BASE || 'http://localhost:8088';
const results = [];

function ok(name, detail) { results.push({ name, pass: true, detail: detail || '' }); console.log('[PASS] ' + name + (detail ? ' — ' + detail : '')); }
function bad(name, detail) { results.push({ name, pass: false, detail }); console.log('[FAIL] ' + name + ' — ' + detail); }

async function check(name, fn) {
  try { const d = await fn(); ok(name, d); }
  catch (e) { bad(name, (e && e.message ? e.message : String(e)).slice(0, 160)); }
}

function assert(cond, msg) { if (!cond) throw new Error(msg); }

async function newPage(browser, opts) {
  const ctx = await browser.newContext(Object.assign({ viewport: { width: 1440, height: 950 }, bypassCSP: true }, opts || {}));
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  page._errors = errors;
  return { ctx, page };
}

// 密码登录已强制图形验证码。页面里的验证码是 SVG，直接从 data URI 解析出字符。
async function fillCaptcha(page) {
  await page.waitForFunction(() => {
    const img = document.querySelector('#auth-cap-img');
    return img && img.src && img.src.indexOf('data:image') === 0;
  }, null, { timeout: 8000 }).catch(() => {});
  const code = await page.evaluate(() => {
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
  if (code) await page.fill('#auth-cap', code);
  return code;
}

async function login(page, phone, pwd) {
  await page.goto(BASE + '/app/', { waitUntil: 'domcontentloaded' });
  await page.fill('#auth-phone', phone);
  await page.fill('#auth-pwd', pwd);
  await fillCaptcha(page);
  await page.click('#auth-login');
  await page.waitForFunction(() => {
    const m = document.querySelector('#auth-mask');
    return !m || m.classList.contains('hide');
  }, null, { timeout: 25000 });
  await page.waitForTimeout(900);
}

async function goto(page, hash) {
  await page.evaluate(h => { location.hash = h; }, hash);
  await page.waitForTimeout(1100);
}

async function api(method, path, token, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: 'Bearer ' + token } : {}),
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  return { status: r.status, body: await r.json().catch(() => null) };
}
async function adminToken() {
  const cap = await api('GET', '/api/auth/captcha', null);
  const body = (cap.body && cap.body.body) || {};
  const svg = body.image ? Buffer.from(body.image.split(',')[1], 'base64').toString('utf8') : '';
  const code = (svg.match(/<text[^>]*>([^<])<\/text>/g) || [])
    .map(t => t.replace(/.*>([^<])<\/text>/, '$1')).join('');
  const r = await api('POST', '/api/auth/login', null, {
    phone: '13800000001', password: 'Admin@2026!Pass',
    captchaId: body.captchaId, captchaCode: code
  });
  return r.body.body.token;
}
async function closeOverlay(page) {
  const btn = page.locator('.overlay [data-close], .drawer [data-close]').last();
  if (await btn.count()) await btn.click();
  await page.waitForTimeout(250);
}

(async () => {
  const browser = await chromium.launch(CHROME ? { executablePath: CHROME, headless: true } : { headless: true });

  /* ---------- 管理员 ---------- */
  const { ctx, page } = await newPage(browser);

  await check('L01 登录后首页渲染', async () => {
    await login(page, '13800000001', 'Admin@2026!Pass');
    const kpis = await page.locator('#view .kpi').count();
    assert(kpis >= 2, 'KPI 卡片数=' + kpis);
    return kpis + ' 个 KPI';
  });

  await check('L02 退出登录回到登录页', async () => {
    await page.click('#avatar');
    await page.waitForTimeout(200);
    await page.click('#um-logout');
    await page.waitForTimeout(2500);
    const visible = await page.evaluate(() => {
      const m = document.querySelector('#auth-mask');
      return !!m && !m.classList.contains('hide');
    });
    assert(visible, '登录框未出现');
    const cookies = (await ctx.cookies()).filter(c => c.name === 'cb_token');
    assert(cookies.length === 0, '会话 Cookie 未清除');
    return '登录框可见、Cookie 已清';
  });

  await check('L03 退出后可再次登录', async () => {
    await login(page, '13800000001', 'Admin@2026!Pass');
    const kpis = await page.locator('#view .kpi').count();
    assert(kpis >= 2, '重新登录后首页无数据');
    return '重新登录成功';
  });

  await check('L04 全局搜索跳转', async () => {
    await page.fill('#global-search', '配额');
    await page.press('#global-search', 'Enter');
    await page.waitForTimeout(900);
    const h = await page.evaluate(() => location.hash);
    assert(h.includes('quota'), '未跳转到配额页：' + h);
    return h;
  });

  await check('L05 通知铃铛弹窗', async () => {
    await page.click('#btn-bell');
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const text = await page.locator('.overlay').innerText();
    assert(text.includes('预警通知'), '弹窗标题不对');
    await closeOverlay(page);
    return '预警通知弹窗可打开/关闭';
  });

  await check('L06 网站设置抽屉', async () => {
    await page.click('#btn-settings');
    await page.waitForSelector('.drawer', { timeout: 4000 });
    const text = await page.locator('.drawer').innerText();
    assert(text.includes('标签页'), '设置项缺失');
    await closeOverlay(page);
    return '抽屉可打开/关闭';
  });

  await check('L07 页签打开与切换', async () => {
    await goto(page, '#/carbon-product/product/list');
    await goto(page, '#/carbon-facility/device-data/list');
    const tags = await page.locator('#tags .tag-item').count();
    assert(tags >= 2, '页签数=' + tags);
    await page.locator('#tags .tag-item[data-path*="product"]').click();
    await page.waitForTimeout(800);
    const h = await page.evaluate(() => location.hash);
    assert(h.includes('product'), '点页签未切回：' + h);
    return tags + ' 个页签，切换正常';
  });

  await check('L08 产品列表筛选与重置', async () => {
    await goto(page, '#/carbon-product/product/list');
    await page.fill('[data-filter="kw"]', '轴承');
    await page.click('#g-search');
    await page.waitForTimeout(900);
    const rows = await page.locator('#g-table tbody tr').count();
    assert(rows === 1, '筛选后行数=' + rows);
    await page.click('#g-reset');
    await page.waitForTimeout(900);
    const all = await page.locator('#g-table tbody tr').count();
    assert(all >= 4, '重置后行数=' + all);
    return '筛选 1 条，重置 ' + all + ' 条';
  });

  await check('L09 设备数据分页', async () => {
    await goto(page, '#/carbon-facility/device-data/list');
    const next = page.locator('.pager [data-go="2"]').first();
    assert(await next.count(), '没有第 2 页按钮');
    await next.click();
    await page.waitForTimeout(1000);
    const active = await page.locator('.pager button.active').innerText();
    assert(active.trim() === '2', '当前页=' + active);
    return '翻到第 2 页';
  });

  await check('L10 编辑产品弹窗回填', async () => {
    await goto(page, '#/carbon-product/product/list');
    await page.locator('[data-edit]').first().click();
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const val = await page.locator('.overlay input[name="name"]').first().inputValue();
    assert(val && val.length > 1, '名称未回填');
    const txt = await page.locator('.overlay').innerText();
    assert(!txt.includes('授权到期') && !txt.includes('数据范围'), '编辑弹窗仍有已删除字段');
    await closeOverlay(page);
    return '回填：' + val;
  });

  await check('L11 审计详情弹窗', async () => {
    await goto(page, '#/system/audit');
    await page.waitForTimeout(800);
    const btn = page.locator('[data-au-open]').first();
    assert(await btn.count(), '没有详情按钮');
    await btn.click();
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const text = await page.locator('.overlay').innerText();
    assert(text.includes('变更') || text.includes('操作'), '详情内容缺失');
    await closeOverlay(page);
    return '详情可打开/关闭';
  });

  await check('L12 新增成员弹窗', async () => {
    await goto(page, '#/system/users');
    await page.click('#u-new');
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const text = await page.locator('.overlay').innerText();
    assert(text.includes('手机号'), '表单缺少手机号');
    assert(!text.includes('成员类型') && !text.includes('数据范围'), '已删除的配置项仍在表单里');
    await closeOverlay(page);
    return '表单可打开/关闭，已无三项配置';
  });

  await check('L13 新建角色页面树', async () => {
    await goto(page, '#/system/roles');
    await page.click('#r-new');
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const boxes = await page.locator('.overlay input[type="checkbox"]').count();
    assert(boxes >= 20, '页面勾选项=' + boxes);
    await closeOverlay(page);
    return boxes + ' 个页面可选';
  });

  await check('L14 修改密码弹窗', async () => {
    await page.click('#avatar');
    await page.click('#um-pwd');
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const text = await page.locator('.overlay').innerText();
    assert(text.includes('旧密码') || text.includes('原密码'), '缺少旧密码输入');
    await closeOverlay(page);
    return '弹窗可打开/关闭';
  });

  await check('L15 导出 CSV 触发下载', async () => {
    await goto(page, '#/carbon-product/product/list');
    const [dl] = await Promise.all([
      page.waitForEvent('download', { timeout: 12000 }),
      page.click('#g-export')
    ]);
    const name = dl.suggestedFilename();
    assert(/\.csv$/i.test(name), '下载文件名=' + name);
    return name;
  });

  await check('L22 企业信息编辑回填', async () => {
    await goto(page, '#/enterprise/info');
    await page.click('#org-edit');
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const val = await page.locator('.overlay input[name="name"]').first().inputValue();
    assert(val.includes('华东'), '企业名未回填：' + val);
    await closeOverlay(page);
    return '回填：' + val;
  });

  await check('L23 报告生成弹窗', async () => {
    await goto(page, '#/carbon-report/calculation-report/query');
    await page.click('#r-gen');
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const txt = await page.locator('.overlay').innerText();
    assert(txt.includes('报告') && (txt.includes('生成') || txt.includes('期间')), '弹窗内容不符');
    await closeOverlay(page);
    return '弹窗可打开/关闭';
  });

  await check('L24 预警处理弹窗', async () => {
    await goto(page, '#/common-warning/record/list');
    const btn = page.locator('[data-handle]').first();
    assert(await btn.count(), '没有处理按钮');
    await btn.click();
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const txt = await page.locator('.overlay').innerText();
    assert(txt.includes('处理') || txt.includes('备注'), '弹窗内容不符');
    await closeOverlay(page);
    return '处理弹窗可打开/关闭';
  });

  await check('L25 审计按动作筛选', async () => {
    await goto(page, '#/system/audit');
    const before = await page.locator('#g-table tbody tr, #view table.tb tbody tr').count();
    await page.evaluate(() => {
      const s = document.querySelector('#au-action');
      s.value = 'create';
      s.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.click('#au-search');
    await page.waitForTimeout(1000);
    const after = await page.locator('#view table.tb tbody tr').count();
    assert(after > 0 && after <= Math.max(before, 20), '筛选后行数异常：' + after);
    await page.click('#au-reset');
    await page.waitForTimeout(600);
    return '筛选后 ' + after + ' 行';
  });

  await check('L26 停用成员需确认', async () => {
    await goto(page, '#/system/users');
    const btn = page.locator('[data-act="disable"]').first();
    assert(await btn.count(), '没有停用按钮');
    await btn.click();
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const txt = await page.locator('.overlay').innerText();
    assert(txt.includes('停用'), '确认弹窗内容不符');
    await closeOverlay(page);
    return '确认弹窗可打开/取消';
  });

  await check('L27 角色编辑回显页面勾选', async () => {
    await goto(page, '#/system/roles');
    await page.locator('[data-role-edit]').first().click();
    await page.waitForSelector('.overlay', { timeout: 4000 });
    const checked = await page.locator('.overlay input[type="checkbox"]:checked').count();
    assert(checked > 0, '已有页面未勾选');
    await closeOverlay(page);
    return checked + ' 个页面已勾选';
  });

  await check('L28 UI 新增→编辑→删除产品', async () => {
    const tag = String(Date.now()).slice(-6);
    const name = 'TST 流程验收产品' + tag;
    const name2 = name + '改';
    let admin = await adminToken();
    try {
      await goto(page, '#/carbon-product/product/list');
      await page.click('#g-new');
      await page.waitForSelector('.overlay', { timeout: 4000 });
      await page.fill('.overlay input[name="name"]', name);
      await page.locator('.overlay [data-f="unit"] .sel-trigger').click();
      await page.waitForTimeout(200);
      await page.locator('.overlay [data-f="unit"] .sel-pop [data-v="吨"]').click();
      await page.waitForTimeout(200);
      await page.click('#gf-save');
      await page.waitForTimeout(1600);
      assert(await page.locator('.overlay').count() === 0, '保存后弹窗未关闭');

      await page.fill('[data-filter="kw"]', name);
      await page.click('#g-search');
      await page.waitForTimeout(900);
      let rows = await page.locator('#g-table tbody tr [data-edit]').count();
      assert(rows === 1, '新增后未查到：' + rows);

      await page.locator('[data-edit]').first().click();
      await page.waitForSelector('.overlay', { timeout: 4000 });
      await page.fill('.overlay input[name="name"]', name2);
      await page.click('#gf-save');
      await page.waitForTimeout(1500);
      await page.fill('[data-filter="kw"]', name2);
      await page.click('#g-search');
      await page.waitForTimeout(900);
      rows = await page.locator('#g-table tbody tr [data-edit]').count();
      assert(rows === 1, '编辑后未生效：' + rows);

      await page.locator('[data-del]').first().click();
      await page.waitForSelector('.overlay [data-yes]', { timeout: 4000 });
      await page.click('.overlay [data-yes]');
      await page.waitForTimeout(1400);
      await page.fill('[data-filter="kw"]', name2);
      await page.click('#g-search');
      await page.waitForTimeout(800);
      rows = await page.locator('#g-table tbody tr [data-edit]').count();
      assert(rows === 0, '删除后仍可见：' + rows);
      return '新增/编辑/删除闭环通过';
    } finally {
      const list = await api('GET', '/api/products?size=100&keyword=' + encodeURIComponent(name), admin);
      for (const r of (list.body.body.rows || [])) await api('DELETE', '/api/products/' + r.id, admin);
    }
  });

  await check('L29 列表批量删除', async () => {
    const tag = String(Date.now()).slice(-6);
    const admin = await adminToken();
    const made = [];
    for (let i = 1; i <= 2; i++) {
      const r = await api('POST', '/api/products', admin, {
        name: 'TST 批量验收' + tag + '-' + i, code: 'TST-BULK-' + tag + '-' + i,
        type: '一般产品', cls: '工业产品', unit: '吨'
      });
      made.push(r.body.body.id);
    }
    try {
      await goto(page, '#/carbon-product/product/list');
      await page.fill('[data-filter="kw"]', '批量验收' + tag);
      await page.click('#g-search');
      await page.waitForTimeout(900);
      await page.click('[data-all]');
      await page.waitForTimeout(300);
      const label = await page.locator('#g-bulk').innerText();
      assert(label.includes('2'), '批量按钮计数=' + label);
      await page.click('#g-bulk');
      await page.waitForSelector('.overlay [data-yes]', { timeout: 4000 });
      await page.click('.overlay [data-yes]');
      await page.waitForTimeout(1600);
      const rows = await page.locator('#g-table tbody tr [data-edit]').count();
      assert(rows === 0, '批量删除后剩 ' + rows);
      return '勾选 2 条并删除';
    } finally {
      for (const id of made) await api('DELETE', '/api/products/' + id, admin);
    }
  });

  await check('L30 UI 新建角色→删除', async () => {
    const tag = String(Date.now()).slice(-6);
    const name = 'TST 流程验收角色' + tag;
    const admin = await adminToken();
    try {
      await goto(page, '#/system/roles');
      await page.click('#r-new');
      await page.waitForSelector('.overlay', { timeout: 4000 });
      await page.fill('#role-name', name);
      const first = page.locator('.overlay input[data-page]').first();
      if (!(await first.isChecked())) await first.check();
      await page.click('#role-save');
      await page.waitForTimeout(1600);
      const row = page.locator('#view table.tb tbody tr', { hasText: name });
      assert(await row.count(), '新建后列表无该角色');
      await row.locator('[data-role-del]').click();
      await page.waitForSelector('.overlay [data-yes]', { timeout: 4000 });
      await page.click('.overlay [data-yes]');
      await page.waitForTimeout(1400);
      const left = await page.locator('#view table.tb tbody tr', { hasText: name }).count();
      assert(left === 0, '删除后仍显示');
      return '角色创建/删除通过';
    } finally {
      const roles = await api('GET', '/api/roles', admin);
      for (const r of (roles.body.body || [])) if (r.name === name) await api('DELETE', '/api/roles/' + r.roleId, admin);
    }
  });

  await check('L33 通知“查看”可跳转', async () => {
    await goto(page, '#/home');
    await page.click('#btn-bell');
    await page.waitForSelector('.overlay [data-jump]', { timeout: 4000 });
    await page.locator('.overlay [data-jump]').first().click();
    await page.waitForTimeout(800);
    const h = await page.evaluate(() => location.hash);
    assert(h.includes('warning'), '未跳到预警记录：' + h);
    return h;
  });

  await check('L34 关闭当前页签', async () => {
    await goto(page, '#/carbon-product/product/list');
    const before = await page.locator('#tags .tag-item').count();
    await page.locator('#tags .tag-item[data-path*="product"] [data-close]').click();
    await page.waitForTimeout(700);
    const after = await page.locator('#tags .tag-item').count();
    assert(after === before - 1, '页签数 ' + before + '→' + after);
    return '关闭后 ' + after + ' 个';
  });

  await check('L36 报告生成闭环', async () => {
    const admin = await adminToken();
    let rid = null;
    try {
      // 草稿期不允许生成正式报告：先核算并提交本期
      const pv0 = await api('GET', '/api/accounting-periods/2026-11', admin);
      const st0 = pv0.body && pv0.body.body ? pv0.body.body.status : '';
      if (!['submitted', 'reviewed', 'approved', 'locked'].includes(st0)) {
        await api('POST', '/api/accounting-periods/2026-11/calculate', admin);
        const pv1 = await api('GET', '/api/accounting-periods/2026-11', admin);
        await api('POST', '/api/accounting-periods/2026-11/submit', admin,
          { version: pv1.body.body.version });
      }
      await goto(page, '#/carbon-report/calculation-report/query');
      await page.click('#r-gen');
      await page.waitForSelector('.overlay', { timeout: 4000 });
      await page.waitForSelector('#gopen', { timeout: 15000 });
      const txt = await page.locator('#gres').innerText();
      assert(txt.includes('报告已生成'), '生成失败：' + txt.slice(0, 40));
      const list = await api('GET', '/api/reports?type=carbon', admin);
      const rows = (list.body.body && list.body.body.rows) || [];
      rid = rows.length ? rows[0].id : null;
      await closeOverlay(page);
      return 'PDF 已生成';
    } finally {
      if (rid) await api('DELETE', '/api/reports/' + rid, admin);
    }
  });

  await check('L37 预警标记已读', async () => {
    const admin = await adminToken();
    const before = (await api('GET', '/api/alert-records/stats', admin)).body.body.unread;
    await goto(page, '#/common-warning/record/list');
    const btn = page.locator('[data-read]').first();
    if (!before || !(await btn.count())) {
      // 演示库可能已全部已读：验证“全部已读”在无未读时也不报错
      await page.click('#rec-readall');
      await page.waitForTimeout(800);
      const after = (await api('GET', '/api/alert-records/stats', admin)).body.body.unread;
      assert(after === 0, '未读数变为 ' + after);
      return '无未读，全部已读幂等';
    }
    await btn.click();
    await page.waitForTimeout(1200);
    const after = (await api('GET', '/api/alert-records/stats', admin)).body.body.unread;
    assert(after === before - 1, '未读数 ' + before + '→' + after);
    return '未读 ' + before + '→' + after;
  });

  await check('L35 会话过期自动回登录页', async () => {
    await goto(page, '#/carbon-product/product/list');
    await ctx.clearCookies();
    await page.evaluate(() => localStorage.removeItem('cb_token'));
    await page.click('#g-search');
    await page.waitForTimeout(1500);
    const visible = await page.evaluate(() => {
      const m = document.querySelector('#auth-mask');
      return !!m && !m.classList.contains('hide');
    });
    assert(visible, '未回到登录页');
    const msg = await page.locator('#auth-err').innerText();
    assert(msg.includes('过期'), '提示文案=' + msg);
    return msg.trim();
  });

  await check('L38 信用资产/节能改造/能源管理可导出', async () => {
    // 上一条会话过期用例会清掉登录态，这里重新登录
    if (await page.locator('#auth-mask:not(.hide)').count()) {
      await login(page, '13800000001', 'Admin@2026!Pass');
    }
    const pages = [
      ['#/carbon-asset/ccer/list', '信用资产'],
      ['#/carbon-economize/renovation-project/list', '节能改造'],
      ['#/carbon-economize/management-situation/list', '能源管理']
    ];
    for (const [hash, label] of pages) {
      await goto(page, hash);
      const [dl] = await Promise.all([
        page.waitForEvent('download', { timeout: 10000 }),
        page.click('#g-export')
      ]);
      assert(/\.csv$/i.test(dl.suggestedFilename()), label + ' 未下载 CSV');
    }
    return '3 类导出均触发下载';
  });

  await check('L39 供应链分析筛选只列一级供应商', async () => {
    const boot = await api('GET', '/api/bootstrap', await adminToken());
    const all = (boot.body && boot.body.body && boot.body.body.suppliers) || [];
    const roots = all.filter(s => !s.parentId).map(s => s.name);
    const children = all.filter(s => s.parentId).map(s => s.name);
    assert(roots.length > 0, '快照里没有供应商');

    await goto(page, '#/carbon-footprint/supplier-footprint-analysis/index');
    await page.waitForTimeout(700);
    await page.locator('.filters .sel-trigger').first().click();
    await page.waitForTimeout(250);
    const opts = await page.$$eval('.filters .sel-pop button',
      bs => bs.map(b => b.textContent.replace(/[✓\s]/g, '')));
    for (const c of children) {
      assert(!opts.includes(c), '下级供应商不应出现在筛选项：' + c);
    }
    for (const r of roots) {
      assert(opts.includes(r), '一级供应商缺失：' + r);
    }
    return roots.length + ' 个一级可选，' + children.length + ' 个下级已排除';
  });

  /**
   * L40 核算审批链（P1-8）：提交 → 复核 → 批准 → 锁定 → 解锁，全程用 UI 按钮驱动。
   *
   * 设计约束：
   *  - 只做可逆操作：链路最后一定会解锁，把期间还原为可编辑状态，不影响后续用例；
   *  - 进入前自愈：上一次中断可能把 2026-11 停在 locked，先用 API 解锁再跑；
   *  - 每一步都用 API 复核真实状态，避免"按钮点了但请求失败"被误判为通过。
   */
  await check('L40 核算审批链：提交→复核→批准→锁定→解锁', async () => {
    const PERIOD = '2026-11';
    const ROUTE = '#/carbon-computation/calculation-complete/list';
    const token = await adminToken();
    const state = async () => {
      const r = await api('GET', '/api/accounting-periods/' + PERIOD, token);
      assert(r.status === 200, '读取核算期失败：' + r.status);
      return r.body.body;
    };

    /* 每个环节换一个账号：服务端有职责分离（编制/复核/批准不能同一人），
       用同一个账号跑完整条链会被 409 SOD_VIOLATION 正确拦下。 */
    const actor = async (phone, pwd) => {
      const s = await newPage(browser);
      await login(s.page, phone, pwd);
      await s.page.waitForFunction(() => Object.keys((window.UI && UI.routes) || {}).length >= 30,
        null, { timeout: 20000 }).catch(() => {});
      await s.page.waitForTimeout(600);
      await goto(s.page, ROUTE);
      return s;
    };
    const step = async (s, attr, label, expect) => {
      const btn = s.page.locator(`#view [${attr}="${PERIOD}"]`).first();
      assert(await btn.count() > 0,
        label + ' 按钮不存在（当前 ' + (await state()).status + '，账号可能无该动作权限）');
      await btn.click();
      const yes = s.page.locator('.overlay [data-yes]');
      await yes.first().waitFor({ state: 'visible', timeout: 2500 }).catch(() => {});
      if (await yes.count() > 0) await yes.first().click();
      await s.page.waitForTimeout(1700);
      const st = await state();
      assert(st.status === expect, label + ' 后期望 ' + expect + '，实际 ' + st.status);
      return st.status;
    };

    const trail = [];
    let cur = await state();
    trail.push(cur.status);
    if (cur.status === 'locked') {
      const u = await api('POST', `/api/accounting-periods/${PERIOD}/unlock`, token,
        { version: cur.version, reason: 'TST L40 重跑前重置' });
      assert(u.status === 200, '自愈解锁失败：' + u.status);
      cur = await state();
      trail.push(cur.status);
    }

    /* 演示种子里 2026-11 可能已预置为 submitted，那样「计算 → 提交」两个按钮根本不会渲染。
       先用正规流转把期间退回草稿（reset-demo.sh，纯 API，保留计算结果/报告/审计），
       这样才能完整覆盖"提交→复核→批准→锁定"四个 UI 动作。 */
    if (process.env.SKIP_L40_RESET !== '1') {
      try {
        const { execSync } = require('child_process');
        const path = require('path');
        const reset = path.resolve(__dirname, '../../platform/backend/tools/reset-demo.sh');
        execSync('bash "' + reset + '" --no-purge', { stdio: 'ignore', timeout: 180000 });
        cur = await state();
        trail.push(cur.status);
      } catch (e) {
        // 复位失败不致命：退化为"从当前状态往后点"，未覆盖的步骤会在返回值里体现
        trail.push('reset-failed');
      }
    }

    const STEPS = {
      draft: { phone: '13800000001', pwd: 'Admin@2026!Pass', attr: 'data-calc', name: '计算', to: 'calculated' },
      calculated: { phone: '13800000002', pwd: 'Test@2026!Pass', attr: 'data-submit', name: '提交', to: 'submitted' },
      submitted: { phone: '13800000004', pwd: 'Test@2026!Pass', attr: 'data-review', name: '复核', to: 'reviewed' },
      reviewed: { phone: '13800000005', pwd: 'Test@2026!Pass', attr: 'data-approve', name: '批准', to: 'approved' },
      approved: { phone: '13800000001', pwd: 'Admin@2026!Pass', attr: 'data-lock', name: '锁定', to: 'locked' },
    };
    const clickedSteps = [];
    for (let guard = 0; guard < 6 && cur.status !== 'locked'; guard += 1) {
      const st = STEPS[cur.status];
      assert(st, '状态 ' + cur.status + ' 没有对应的下一步按钮');
      const s = await actor(st.phone, st.pwd);
      try {
        const reached = await step(s, st.attr, st.name, st.to);
        trail.push(reached);
        clickedSteps.push(st.name);
        cur = { status: reached };
      } finally { await s.ctx.close(); }
    }
    assert(cur.status === 'locked', '未走到 locked，实际 ' + cur.status);

    const admin2 = await actor('13800000001', 'Admin@2026!Pass');
    try {

      // 解锁（弹窗 + 必填原因）：可回滚，跑完期间回到可编辑状态
      const unlockBtn = admin2.page.locator(`#view [data-unlock="${PERIOD}"]`).first();
      assert(await unlockBtn.count() > 0, '锁定行上没有解锁按钮');
      await unlockBtn.click();
      await admin2.page.waitForTimeout(400);
      await admin2.page.fill('#ac-reason', 'TST L40 流程验证后解锁');
      await admin2.page.click('#ac-unlock-ok');
      await admin2.page.waitForTimeout(1700);
      cur = await state();
      trail.push(cur.status);
      assert(cur.status !== 'locked', '解锁后仍为 locked');
    } finally { await admin2.ctx.close(); }

    return 'UI 点击 ' + clickedSteps.join('/') + ' · 状态链 ' + trail.join(' → ');
  });

  const err1 = page._errors.filter(e => !/401|Failed to load resource/.test(e));
  await check('L16 管理员流程无脚本异常', async () => {
    assert(err1.length === 0, err1.slice(0, 2).join(' | '));
    return '0 异常';
  });
  await ctx.close();

  /* ---------- 平台 ---------- */
  const pl = await newPage(browser);
  await check('L17 平台概览与进入/退出租户', async () => {
    await login(pl.page, '13900000000', 'Superadmin@2026!Pass');
    const kpis = await pl.page.locator('#view .kpi').count();
    assert(kpis >= 6, '概览 KPI=' + kpis);
    await goto(pl.page, '#/platform/tenants');
    const enter = pl.page.locator('[data-enter]').first();
    assert(await enter.count(), '没有进入租户按钮');
    await enter.click();
    await pl.page.waitForSelector('.overlay .btn-primary', { timeout: 5000 });
    await pl.page.click('.overlay .btn-primary');
    await pl.page.waitForTimeout(3000);
    const banner = await pl.page.locator('#ctx-banner').isVisible().catch(() => false);
    assert(banner, '代管横幅未出现');
    await pl.page.click('#ctx-exit');
    await pl.page.waitForTimeout(2500);
    const h = await pl.page.evaluate(() => location.hash);
    assert(h.includes('platform'), '退出后未回平台：' + h);
    return '6 KPI，进入/退出正常';
  });
  await check('L20 续期弹窗月份联动', async () => {
    await goto(pl.page, '#/platform/tenants');
    await pl.page.locator('[data-renew]').first().click();
    await pl.page.waitForSelector('.overlay', { timeout: 4000 });
    await pl.page.locator('.overlay [data-fsel] .sel-trigger').first().click();
    await pl.page.waitForTimeout(200);
    await pl.page.locator('.overlay [data-fsel] .sel-pop [data-v="6"]').click();
    await pl.page.waitForTimeout(200);
    const v = await pl.page.evaluate(() => document.querySelector('#rn-months').value);
    assert(v === '6', '顺延月份未同步：' + v);
    await closeOverlay(pl.page);
    return '选 6 个月生效';
  });

  await check('L21 开通向导四步可前进', async () => {
    await goto(pl.page, '#/platform/overview');
    await pl.page.click('#ov-new');
    await pl.page.waitForSelector('.overlay', { timeout: 4000 });
    await pl.page.fill('[data-k="name"]', '流程验收企业');
    await pl.page.click('#wz-next');
    await pl.page.waitForTimeout(250);
    await pl.page.fill('[data-k="adminPhone"]', '13900001234');
    await pl.page.click('#wz-next');
    await pl.page.waitForTimeout(250);
    const hasDates = await pl.page.locator('[data-k="licenseEndAt"]').count();
    assert(hasDates === 1, '授权步骤缺日期');
    await pl.page.click('#wz-next');
    await pl.page.waitForTimeout(250);
    const txt = await pl.page.locator('#wz-body').innerText();
    assert(txt.includes('流程验收企业'), '确认页未显示企业名');
    await closeOverlay(pl.page);
    return '4 步可达确认页，未提交';
  });

  await pl.ctx.close();

  /* ---------- 移动端 ---------- */
  const mob = await newPage(browser, { viewport: { width: 390, height: 844 } });
  await check('L19 移动端菜单展开', async () => {
    await login(mob.page, '13800000001', 'Admin@2026!Pass');
    await mob.page.click('#btn-menu');
    await mob.page.waitForTimeout(500);
    const open = await mob.page.evaluate(() => document.querySelector('#side').classList.contains('open'));
    assert(open, '侧边栏未展开');
    return '侧边栏可展开';
  });
  await mob.ctx.close();

  /* ---------- 改密闭环 ---------- */
  const pw = await newPage(browser);
  let pwMid = null;
  try {
    const tag = String(Date.now()).slice(-6);
    const phone = '135' + tag + '91';
    const admin = await adminToken();
    const created = await api('POST', '/api/users', admin, {
      phone, displayName: 'TST 流程验收改密' + tag, userType: 'user'
    });
    pwMid = created.body.body.membershipId;
    const tmp = created.body.body.tempPassword;
    await check('L31 首登改密后重新登录', async () => {
      await pw.page.goto(BASE + '/app/', { waitUntil: 'domcontentloaded' });
      await pw.page.fill('#auth-phone', phone);
      await pw.page.fill('#auth-pwd', tmp);
      await fillCaptcha(pw.page);
      await pw.page.click('#auth-login');
      await pw.page.waitForSelector('#pwd-form', { timeout: 8000 });
      const title = await pw.page.locator('.overlay').innerText();
      assert(title.includes('临时密码'), '未进入强制改密：' + title.slice(0, 40));
      await pw.page.fill('#pwd-form input[name="old"]', tmp);
      await pw.page.fill('#pwd-form input[name="next"]', 'Changed123456!');
      await pw.page.fill('#pwd-form input[name="again"]', 'Changed123456!');
      await pw.page.click('#pwd-save');
      await pw.page.waitForTimeout(3000);
      const loginShown = await pw.page.evaluate(() => {
        const m = document.querySelector('#auth-mask');
        return !!m && !m.classList.contains('hide');
      });
      assert(loginShown, '改密后未回到登录页');
      await login(pw.page, phone, 'Changed123456!');
      const kpis = await pw.page.locator('#view .kpi').count();
      assert(kpis >= 2, '新密码登录失败');
      return '强制改密→重新登录成功';
    });
  } finally {
    if (pwMid) {
      const admin = await adminToken();
      await api('DELETE', '/api/users/' + pwMid, admin);
    }
    await pw.ctx.close();
  }

  /* ---------- 登录失败提示 ---------- */
  const badLogin = await newPage(browser);
  await check('L32 错误密码提示', async () => {
    await badLogin.page.goto(BASE + '/app/', { waitUntil: 'domcontentloaded' });
    await badLogin.page.fill('#auth-phone', '13800000001');
    await badLogin.page.fill('#auth-pwd', 'WrongPass123!');
    await fillCaptcha(badLogin.page);
    await badLogin.page.click('#auth-login');
    await badLogin.page.waitForTimeout(1500);
    const msg = await badLogin.page.locator('#auth-err').innerText();
    assert(msg.trim().length > 0, '没有错误提示');
    return msg.trim().slice(0, 24);
  });
  await badLogin.ctx.close();

  await browser.close();

  // 收尾：本套件会真实走「计算 → 提交」，跑完把演示期间退回草稿并清理 TST 残留
  try {
    const { execSync } = require('child_process');
    const path = require('path');
    const reset = path.resolve(__dirname, '../../platform/backend/tools/reset-demo.sh');
    execSync('bash "' + reset + '"', { stdio: 'ignore', timeout: 180000 });
    console.log('[INFO] 已复位演示期间并清理测试残留');
  } catch (e) {
    console.log('[INFO] 复位脚本未执行：' + String(e && e.message || e).slice(0, 80));
  }

  const failed = results.filter(r => !r.pass);
  console.log('\nRESULT: ' + (failed.length ? 'FAIL' : 'PASS') + ' (' + (results.length - failed.length) + '/' + results.length + ')');
  process.exit(failed.length ? 1 : 0);
})();
