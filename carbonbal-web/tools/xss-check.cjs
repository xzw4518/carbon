#!/usr/bin/env node
/*
 * L0.5 前端 XSS 静态断言（零依赖、可离线跑）。
 *
 * 背景（P1-5）：桑基图节点文本与悬浮提示曾把后端可控字符串（产品/供应商/能源品种名称）
 * 直接拼进 innerHTML，形成存储型 XSS。修复后必须能"随时验证修复没被改回去"，
 * 因此这里用源码断言把规则固化下来，而不是靠一次人工点检。
 *
 * 用法：node tools/xss-check.cjs [--strict]
 *   默认：有 FAIL 退出码 1；只有 WARN 时退出码 0
 *   --strict：WARN 也视为失败（CI 可选用）
 *
 * 断言编号与含义见每条 detail；不依赖任何三方库，纯 fs + 正则。
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const STRICT = process.argv.includes('--strict');
const results = [];
const ok = (id, name, detail) => results.push({ id, name, pass: true, detail });
const bad = (id, name, detail) => results.push({ id, name, pass: false, detail });
const warn = (id, name, detail) => results.push({ id, name, pass: true, warn: true, detail });

const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const asset = (f) => 'assets/' + f;
const assets = fs.readdirSync(path.join(ROOT, 'assets')).filter((f) => f.endsWith('.js'));

/* X01 转义入口必须覆盖 5 个危险字符（& < > " '） */
{
  const fw = read(asset('framework.js'));
  // 按行取 esc 定义：转义表本身含分号（&amp;），不能用 [^;]+ 截断
  const def = fw.split('\n').find((l) => /const esc = \(s\) =>/.test(l)) || '';
  const need = ['&amp;', '&lt;', '&gt;', '&quot;', '&#39;'];
  const miss = need.filter((k) => !def.includes(k));
  if (!def) bad('X01', '转义入口 UI.esc', 'framework.js 找不到 esc 定义');
  else if (miss.length) bad('X01', '转义入口 UI.esc', '未覆盖: ' + miss.join(', '));
  else if (!/window\.UI\s*=\s*\{[^}]*esc/.test(fw)) bad('X01', '转义入口 UI.esc', 'esc 未导出到 UI');
  else ok('X01', '转义入口 UI.esc', '覆盖 & < > " \'，已导出 UI.esc');
}

/* X02 桑基图节点文本必须转义（P1-5 第 1041 行回归点） */
{
  const t = read(asset('pages-generic.js'));
  const m = t.match(/<text[^>]*>\$\{txt\}<\/text>/);
  const escaped = /<text[^>]*>\$\{esc\(txt\)\}<\/text>/.test(t);
  if (m) bad('X02', '桑基节点文本转义', '仍有未转义的 ${txt} 直接写入 <text>');
  else if (!escaped) bad('X02', '桑基节点文本转义', '找不到 ${esc(txt)}，桑基渲染可能被改写');
  else ok('X02', '桑基节点文本转义', '${esc(txt)}');
}

/* X03 innerHTML / outerHTML / insertAdjacentHTML 中不得直接插入 dataset 值 */
{
  const hits = [];
  assets.forEach((f) => {
    const lines = read(asset(f)).split('\n');
    lines.forEach((line, i) => {
      if (!/(innerHTML|outerHTML|insertAdjacentHTML)/.test(line)) return;
      if (/\$\{\s*[a-zA-Z_$][\w$]*\.dataset\./.test(line) || /\+\s*[a-zA-Z_$][\w$]*\.dataset\./.test(line)) {
        hits.push(`${f}:${i + 1}`);
      }
    });
  });
  hits.length
    ? bad('X03', 'dataset→HTML 通道', '未转义 dataset 拼进 HTML: ' + hits.join(', '))
    : ok('X03', 'dataset→HTML 通道', '0 处');
}

/* X04 悬浮提示必须用 DOM 文本节点（replaceChildren / textContent），不用 innerHTML */
{
  const t = read(asset('pages-generic.js'));
  const hasReplace = /tip\.replaceChildren\(/.test(t);
  const innerHtmlTip = /tip\.innerHTML\s*=/.test(t);
  if (innerHtmlTip) bad('X04', 'tooltip 文本节点', 'sk-tip 仍用 innerHTML 赋值');
  else if (!hasReplace) bad('X04', 'tooltip 文本节点', '找不到 tip.replaceChildren，绑定逻辑可能被改写');
  else ok('X04', 'tooltip 文本节点', 'setTip 用 createElement + textContent');
}

/* X05 禁止 document.write / eval / new Function（前端唯一执行面） */
{
  const hits = [];
  assets.forEach((f) => {
    const t = read(asset(f));
    ['document.write(', 'eval(', 'new Function('].forEach((k) => { if (t.includes(k)) hits.push(f + ' → ' + k); });
  });
  hits.length ? bad('X05', '危险执行面', hits.join(', ')) : ok('X05', '危险执行面', '0 处');
}

/* X06 提示性检查：innerHTML 模板里出现未包装的插值，人工可复核（WARN） */
{
  const ALLOW = /^(esc|num|int|icon|fmtV|pad|round|opt|rows|cols|safe|v|n|i|k|t|r|d|b|s|x|y)\b|^[0-9'"`]/;
  const risky = [];
  assets.forEach((f) => {
    read(asset(f)).split('\n').forEach((line, i) => {
      if (!/innerHTML\s*[+]?=/.test(line)) return;
      const exprs = [...line.matchAll(/\$\{([^}]*)\}/g)].map((m) => m[1].trim());
      exprs.forEach((e) => {
        if (!e) return;
        if (/^esc\(|^num\(|^int\(|^icon\(|^fmtV\(|^UI\.esc\(|^pad\(/.test(e)) return;
        if (/^[\d\s+\-*/%().]+$/.test(e)) return;            // 纯算术
        if (/^'[^']*'$|^"[^"]*"$/.test(e)) return;            // 纯字面量
        if (ALLOW.test(e) && !/name|title|text|label|desc|remark/i.test(e)) return;
        if (/name|title|text|label|desc|remark/i.test(e)) risky.push(`${f}:${i + 1} → \${${e}}`);
      });
    });
  });
  if (risky.length) warn('X06', 'innerHTML 插值复核', risky.length + ' 处：' + risky.slice(0, 5).join(' | '));
  else ok('X06', 'innerHTML 插值复核', '无可疑插值');
}

/* 输出 */
const width = Math.max(...results.map((r) => r.name.length));
console.log(`CASE 前端 XSS 静态断言  node ${process.version}  ${new Date().toISOString()}\n`);
results.forEach((r) => {
  const tag = !r.pass ? 'FAIL' : r.warn ? 'WARN' : 'PASS';
  console.log(`[${tag}] ${r.id} ${r.name.padEnd(width)}  ${r.detail}`);
});
const fails = results.filter((r) => !r.pass).length;
const warns = results.filter((r) => r.warn).length;
console.log(`\nRESULT: ${fails === 0 ? (STRICT && warns ? 'FAIL' : 'PASS') : 'FAIL'} (${results.length - fails}/${results.length}${warns ? `, warn=${warns}` : ''})`);
process.exit(fails === 0 && !(STRICT && warns) ? 0 : 1);
