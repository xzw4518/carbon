#!/usr/bin/env node
/* L0 静态门禁：零依赖。用法：node tools/gate-l0.cjs */
'use strict';
const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const A = p => path.join(ROOT, p);
const results = [];
const ok = (id, name, detail) => results.push({ id, name, pass: true, detail });
const bad = (id, name, detail) => results.push({ id, name, pass: false, detail });

/* F01 必填文件 */
const required = ['index.html', 'VERSION', 'assets/app.css', 'assets/menu.js', 'assets/framework.js',
  'assets/pages-core.js', 'assets/pages-generic.js', 'assets/topbar.js', 'assets/account.js'];
{
  const miss = required.filter(f => !fs.existsSync(A(f)));
  miss.length ? bad('F01', '必填文件', '缺失: ' + miss.join(', ')) : ok('F01', '必填文件', required.length + ' 个');
}

/* F02 JS 语法 */
{
  const files = fs.readdirSync(A('assets')).filter(f => f.endsWith('.js'));
  const fails = [];
  files.forEach(f => {
    const r = cp.spawnSync(process.execPath, ['--check', A('assets/' + f)], { encoding: 'utf8' });
    if (r.status !== 0) fails.push(f + ': ' + (r.stderr || '').split('\n')[0]);
  });
  fails.length ? bad('F02', 'JS 语法', fails.join(' | ')) : ok('F02', 'JS 语法', files.length + ' 个');
}

/* F03 菜单路由均有声明 */
{
  const menu = fs.readFileSync(A('assets/menu.js'), 'utf8').match(/path: '([^']+)'/g) || [];
  const paths = [...new Set(menu.map(s => s.replace(/path: '/, '').replace(/'$/, '')))];
  let declared = [];
  fs.readdirSync(A('assets')).filter(f => f.endsWith('.js')).forEach(f => {
    const t = fs.readFileSync(A('assets/' + f), 'utf8');
    declared = declared.concat((t.match(/route\('([^']+)'/g) || []).map(s => s.slice(7, -1)));
    declared = declared.concat((t.match(/path: '(\/carbon-report\/[^']+)'/g) || []).map(s => s.replace(/path: '/, '').replace(/'$/, '')));
  });
  const miss = paths.filter(p => !declared.includes(p));
  miss.length ? bad('F03', '菜单→路由', '未声明: ' + miss.join(', ')) : ok('F03', '菜单→路由', paths.length + ' 个入口');
}

/* F04 图标名称存在 */
{
  const fw = fs.readFileSync(A('assets/framework.js'), 'utf8');
  const block = fw.slice(fw.indexOf('const ICONS = {'), fw.indexOf('\n  };', fw.indexOf('const ICONS = {')));
  const keys = [...block.matchAll(/^\s{4}([a-z0-9-]+):/gm)].map(m => m[1]);
  const used = new Set();
  fs.readdirSync(A('assets')).filter(f => f.endsWith('.js')).forEach(f => {
    const t = fs.readFileSync(A('assets/' + f), 'utf8');
    for (const m of t.matchAll(/icon\('([a-z0-9-]+)'/g)) used.add(m[1]);
  });
  const miss = [...used].filter(x => !keys.includes(x));
  miss.length ? bad('F04', '图标引用', '未定义: ' + miss.join(', ')) : ok('F04', '图标引用', used.size + ' 种');
}

/* F05 禁原生阻塞弹窗 */
{
  const hits = [];
  fs.readdirSync(A('assets')).filter(f => f.endsWith('.js')).forEach(f => {
    const t = fs.readFileSync(A('assets/' + f), 'utf8');
    if (/window\.(confirm|alert|prompt)\s*\(/.test(t)) hits.push(f);
  });
  hits.length ? bad('F05', '原生弹窗', hits.join(', ')) : ok('F05', '原生弹窗', '0 处');
}

/* F06 index 本地引用存在 */
{
  const html = fs.readFileSync(A('index.html'), 'utf8');
  const refs = [...html.matchAll(/(?:src|href)="([^"]+)"/g)].map(m => m[1])
    .filter(u => !/^(https?:|data:|#|mailto:|tel:|javascript:)/.test(u));
  const miss = refs.filter(u => !fs.existsSync(A(u.split('?')[0])));
  miss.length ? bad('F06', '静态资源引用', '缺失: ' + miss.join(', ')) : ok('F06', '静态资源引用', refs.length + ' 个');
}

/* F07 关键数据集骨架存在（数据由 /api/bootstrap 注入） */
{
  const html = fs.readFileSync(A('index.html'), 'utf8');
  const need = ['org','quota','products','prodValue','totalValue','assets','ccers','trades','footprint',
    'evidences','thirdReports','facilities','deviceData','warningEvents','alertRules','alertRecords','energyMgmt','retrofits','suppliers','accounting'];
  const miss = need.filter(k => !new RegExp('\\b' + k + '\\s*:').test(html));
  miss.length ? bad('F07', '关键数据集', '缺失: ' + miss.join(', ')) : ok('F07', '关键数据集', need.length + ' 项');
}

/* F08 index 内 id 不重复 */
{
  const html = fs.readFileSync(A('index.html'), 'utf8');
  const ids = [...html.matchAll(/\sid="([^"]+)"/g)].map(m => m[1]);
  const dup = ids.filter((x, i) => ids.indexOf(x) !== i);
  dup.length ? bad('F08', 'DOM id 唯一', '重复: ' + [...new Set(dup)].join(', ')) : ok('F08', 'DOM id 唯一', ids.length + ' 个');
}

/* F09 字号阶梯（M09 断言：正文种类 ≤10 且无 <10px） */
{
  const css = fs.readFileSync(A('assets/app.css'), 'utf8');
  const vals = [...css.matchAll(/font-size:\s*([0-9.]+)px/g)].map(m => Number(m[1]));
  const DISPLAY = [21, 22, 26, 40]; // 展示型白名单：页面标题 / KPI 数值 / 水印
  const text = vals.filter(v => !DISPLAY.includes(v));
  const kinds = [...new Set(text)].sort((a, b) => a - b);
  const small = text.filter(v => v < 10);
  if (!text.length) bad('F09', '字号阶梯', '未解析到 font-size');
  else if (small.length) bad('F09', '字号阶梯', '<10px 出现 ' + small.length + ' 次');
  else if (kinds.length > 10) bad('F09', '字号阶梯', '正文/控件 ' + kinds.length + ' 种（上限 10）: ' + kinds.join('/'));
  else ok('F09', '字号阶梯', '正文 ' + kinds.length + ' 种（' + kinds.join('/') + '），展示型 ' + DISPLAY.length + ' 种白名单');
}

/* F10 路由无重复声明、菜单无空名 */
{
  const menu = fs.readFileSync(A('assets/menu.js'), 'utf8');
  const paths = [...menu.matchAll(/path:\s*'([^']+)'/g)].map(m => m[1]);
  const dup = paths.filter((x, i) => paths.indexOf(x) !== i);
  dup.length ? bad('F10', '菜单唯一性', '重复路径: ' + [...new Set(dup)].join(', ') ) : ok('F10', '菜单唯一性', paths.length + ' 条路径无重复');
}

/* F11 无 mock 残留（前端直连真实接口） */
{
  const files = ['index.html', ...fs.readdirSync(A('assets')).filter(f => /\.(js|css)$/.test(f)).map(f => 'assets/' + f)];
  const hits = [];
  files.forEach(f => {
    const t = fs.readFileSync(A(f), 'utf8');
    if (/assets\/data\.js|window\.CarbonAPI\s*&&\s*CarbonAPI\.request|离线演示|原型模拟|原型演示|固定码 123456/.test(t)) hits.push(f);
  });
  const bytes = files.reduce((n, f) => n + fs.statSync(A(f)).size, 0);
  const LIMIT = 2 * 1024 * 1024;
  if (hits.length) bad('F11', 'mock 残留', hits.join(', '));
  else if (bytes > LIMIT) bad('F11', '体积', Math.round(bytes / 1024) + 'KB > 2048KB');
  else ok('F11', 'mock 残留', '0 处，体积 ' + Math.round(bytes / 1024) + 'KB');
}

/* F12 XSS 静态断言（P1-5 回归护栏）：直接复用 tools/xss-check.cjs 的结论 */
{
  const script = A('tools/xss-check.cjs');
  if (!fs.existsSync(script)) bad('F12', 'XSS 断言', '缺少 tools/xss-check.cjs');
  else {
    const r = cp.spawnSync(process.execPath, [script], { encoding: 'utf8' });
    const line = (r.stdout || '').split('\n').filter(l => /^RESULT:/.test(l))[0] || '(无输出)';
    if (r.status === 0) ok('F12', 'XSS 断言', line.replace('RESULT: ', ''));
    else bad('F12', 'XSS 断言', (r.stdout || '').split('\n').filter(l => /^\[FAIL\]|^\[WARN\]/.test(l)).slice(0, 3).join(' | ') || line);
  }
}

/* 输出 */
const VERSION = fs.existsSync(A('VERSION')) ? fs.readFileSync(A('VERSION'), 'utf8').trim() : 'unversioned';
const width = Math.max(...results.map(r => r.name.length));
console.log(`CASE 能碳管理平台  version ${VERSION}  node ${process.version}  ${new Date().toISOString()}\n`);
results.forEach(r => console.log(`[${r.pass ? 'PASS' : 'FAIL'}] ${r.id} ${r.name.padEnd(width)}  ${r.detail}`));
const f = results.filter(r => !r.pass).length;
console.log(`\nRESULT: ${f === 0 ? 'PASS' : 'FAIL'} (${results.length - f}/${results.length})`);

/* 趋势采集：供「首次门禁通过率」统计（M14 / §9.8.1） */
try {
  const dir = A('docs/verification');
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  fs.appendFileSync(path.join(dir, 'gate_history.jsonl'), JSON.stringify({
    at: new Date().toISOString(), version: VERSION, pass: f === 0,
    total: results.length, fail: results.filter(r => !r.pass).map(r => r.id)
  }) + '\n');
} catch (e) {
  console.log('WARN 历史落盘失败: ' + e.message);
}
process.exit(f === 0 ? 0 : 1);
