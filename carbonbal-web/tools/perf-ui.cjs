/* 首屏性能采样：登录后统计请求数、传输量、FCP/LCP/DOM 节点
   用法：node tools/perf-ui.cjs [BASE_URL] （需 playwright-core） */
const fs = require('fs');
const path = require('path');

const BASE = process.argv[2] || 'http://localhost:8088';
const CHROME = [
  process.env.CHROME_PATH,
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  '/usr/bin/google-chrome',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
].filter(Boolean).find(p => fs.existsSync(p));

(async () => {
  const { chromium } = require('playwright-core');
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 950 } });
  const errors = [];
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  let reqCount = 0;
  page.on('request', () => { reqCount++; });

  await page.addInitScript(() => {
    window.__lcp = 0;
    try {
      new PerformanceObserver(list => {
        for (const e of list.getEntries()) window.__lcp = e.startTime;
      }).observe({ type: 'largest-contentful-paint', buffered: true });
    } catch (e) { /* 浏览器不支持则忽略 */ }
  });

  const t0 = Date.now();
  await page.goto(BASE + '/app/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(700);
  await page.fill('#auth-phone', '13800000001');
  await page.fill('#auth-pwd', 'Admin@2026!Pass');
  await page.click('#auth-login');
  await page.waitForTimeout(3000);

  const m = await page.evaluate(() => {
    const nav = performance.getEntriesByType('navigation')[0] || {};
    const paint = performance.getEntriesByType('paint') || [];
    const fcp = (paint.find(p => p.name === 'first-contentful-paint') || {}).startTime || 0;
    const res = performance.getEntriesByType('resource') || [];
    const transfer = res.reduce((n, r) => n + (r.transferSize || 0), 0);
    return {
      domContentLoaded: Math.round(nav.domContentLoadedEventEnd || 0),
      load: Math.round(nav.loadEventEnd || 0),
      fcp: Math.round(fcp),
      lcp: Math.round(window.__lcp || 0),
      resources: res.length,
      transferKB: Math.round(transfer / 1024),
      domNodes: document.querySelectorAll('*').length,
    };
  });

  const realErrors = errors.filter(e => !/401/.test(e)); // 未登录时的会话探测 401 属预期
  const out = { ...m, requests: reqCount, consoleErrors: realErrors.length, loginMs: Date.now() - t0 };
  console.log(JSON.stringify(out));
  await browser.close();
})().catch(e => {
  console.error('PERF_UI_ERROR ' + e.message);
  process.exit(1);
});
