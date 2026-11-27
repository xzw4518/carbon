/* 轻量框架：路由 / 组件 / 交互 / 图表 */
(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const num = (n, d = 1) => (n == null || isNaN(n)) ? '—' : Number(n).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d });
  const int = (n) => (n == null || isNaN(n)) ? '—' : Number(n).toLocaleString('zh-CN');

  const ICONS = {
    gauge: '<path d="M12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z"/><path d="M13.4 10.6 17 7"/><path d="M20.4 16a9 9 0 1 0-16.8 0"/>',
    ledger: '<path d="M5 4h11l3 3v13H5z"/><path d="M8 9h8M8 13h8M8 17h5"/>',
    wallet: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M3 10h18"/><circle cx="16.5" cy="14" r="1.2"/>',
    leaf: '<path d="M5 19c0-7 5-12 14-13 0 9-5 14-11 14"/><path d="M5 19c3-4 6-6 9-7"/>',
    file: '<path d="M7 3h7l4 4v14H7z"/><path d="M14 3v5h5"/><path d="M10 12h6M10 16h6"/>',
    cpu: '<rect x="7" y="7" width="10" height="10" rx="2"/><path d="M10 3v2M14 3v2M10 19v2M14 19v2M3 10h2M3 14h2M19 10h2M19 14h2"/>',
    bell: '<path d="M6 16V11a6 6 0 0 1 12 0v5"/><path d="M4 16h16"/><path d="M10 19a2 2 0 0 0 4 0"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4"/>',
    user: '<circle cx="12" cy="8" r="3.4"/><path d="M4.5 20c.6-4 3.7-6 7.5-6s6.9 2 7.5 6"/>',
    search: '<circle cx="11" cy="11" r="6"/><path d="m16 16 4 4"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    download: '<path d="M12 4v10"/><path d="m8 11 4 4 4-4"/><path d="M5 19h14"/>',
    upload: '<path d="M12 16V6"/><path d="m8 9 4-4 4 4"/><path d="M5 19h14"/>',
    x: '<path d="m6 6 12 12M18 6 6 18"/>',
    menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
    filter: '<path d="M4 6h16l-6 7v5l-4 2v-7z"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    alert: '<path d="M12 4 3 20h18z"/><path d="M12 10v4M12 17h.01"/>',
    trash: '<path d="M4 7h16M9 7V5h6v2M6 7l1 13h10l1-13"/>',
    eye: '<path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z"/><circle cx="12" cy="12" r="2.6"/>',
    refresh: '<path d="M20 12a8 8 0 1 1-2.3-5.7"/><path d="M20 4v5h-5"/>',
    lock: '<rect x="5" y="10" width="14" height="10" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
    chart: '<path d="M4 19V9M10 19V5M16 19v-7M22 19H2"/>',
    tree: '<path d="M12 4v6M6 20v-4h12v4"/><rect x="9" y="10" width="6" height="4" rx="1"/>',
  };
  const icon = (name, size = 17) => `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${ICONS[name] || ''}</svg>`;

  const state = { route: '', demoReportFail: true, density: 'comfortable', role: 'admin' };
  const can = (p) => state.role === 'admin' || !['export', 'report', 'delete'].includes(p);

  function toast(type, title, desc = '') {
    const box = $('#toasts');
    const node = document.createElement('div');
    node.className = 'toast ' + type;
    node.innerHTML = `<span style="color:${type === 'ok' ? 'var(--ok-600)' : type === 'err' ? 'var(--danger-600)' : type === 'warn' ? 'var(--accent-600)' : 'var(--brand-600)'}">${icon(type === 'ok' ? 'check' : type === 'err' ? 'alert' : 'bell')}</span>
      <div><b>${esc(title)}</b>${desc ? `<span>${esc(desc)}</span>` : ''}</div>`;
    box.appendChild(node);
    setTimeout(() => { node.style.transition = '.25s'; node.style.opacity = '0'; node.style.transform = 'translateX(10px)'; }, 3200);
    setTimeout(() => node.remove(), 3600);
  }

  function modal({ title, body, foot = '', width = 620, onMount, onClose }) {
    const wrap = document.createElement('div');
    wrap.className = 'overlay';
    wrap.innerHTML = `<div class="modal" style="width:${width}px" role="dialog" aria-modal="true">
      <div class="modal-head"><h3 class="modal-title">${esc(title)}</h3>
        <button class="icon-btn modal-x" data-close aria-label="关闭">${icon('x', 16)}</button></div>
      <div class="modal-body">${body}</div>
      ${foot ? `<div class="modal-foot">${foot}</div>` : ''}</div>`;
    document.body.appendChild(wrap);
    document.body.style.overflow = 'hidden';
    let done = false;
    const close = () => {
      if (done) return; done = true;
      wrap.remove();
      if (!document.querySelector('.overlay')) document.body.style.overflow = '';
      document.removeEventListener('keydown', onKey);
      onClose && onClose();
    };
    const onKey = (e) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', onKey);
    wrap.addEventListener('mousedown', (e) => { if (e.target === wrap) close(); });
    wrap.querySelectorAll('[data-close]').forEach(b => b.onclick = close);
    onMount && onMount(wrap, close);
    enhanceForm(wrap);
    return { el: wrap, close };
  }

  function confirm({ title = '提示', content, okText = '确定', danger = false }) {
    return new Promise((resolve) => {
      let settled = false;
      const finish = (v) => { if (!settled) { settled = true; resolve(v); } };
      modal({
        title, width: 430,
        body: `<div style="font-size:13.5px;color:var(--ink-700)">${content}</div>`,
        foot: `<button class="btn" data-no>取消</button><button class="btn ${danger ? 'btn-danger' : 'btn-primary'}" data-yes>${esc(okText)}</button>`,
        onMount(wrap, close) {
          wrap.querySelector('[data-no]').onclick = () => { finish(false); close(); };
          wrap.querySelector('[data-yes]').onclick = () => { finish(true); close(); };
        },
        onClose: () => finish(false),
      });
    });
  }

  function drawer({ title, body, onMount }) {
    const mask = document.createElement('div');
    mask.className = 'drawer-mask';
    const d = document.createElement('div');
    d.className = 'drawer';
    d.innerHTML = `<div class="drawer-head"><h3 class="modal-title">${esc(title)}</h3>
      <button class="icon-btn right" data-close aria-label="关闭">${icon('x', 16)}</button></div>
      <div class="drawer-body">${body}</div>`;
    document.body.append(mask, d);
    document.body.style.overflow = 'hidden';
    const close = () => { mask.remove(); d.remove(); document.body.style.overflow = ''; document.removeEventListener('keydown', onKey); };
    const onKey = (e) => { if (e.key === 'Escape') close(); };
    document.addEventListener('keydown', onKey);
    mask.onclick = close;
    d.querySelector('[data-close]').onclick = close;
    onMount && onMount(d, close);
    enhanceForm(d);
    return { el: d, close };
  }

  function busy(btn, label = '处理中') {
    if (!btn) return () => {};
    const html = btn.innerHTML;
    btn.classList.add('is-disabled');
    btn.disabled = true;
    btn.innerHTML = `<span class="spin"></span>${esc(label)}`;
    return () => { btn.classList.remove('is-disabled'); btn.disabled = false; btn.innerHTML = html; };
  }
  const sleep = (ms) => new Promise(r => setTimeout(r, ms));

  function trendChart(rows) {
    const w = 720, h = 240, pad = { l: 44, r: 16, t: 14, b: 26 };
    const iw = w - pad.l - pad.r, ih = h - pad.t - pad.b;
    const rawMax = Math.max(...rows.map(r => r.emission)) * 1.12;
    const step = Math.max(100, Math.ceil(rawMax / 4 / 100) * 100);
    const maxE = step * 4, maxN = Math.max(...rows.map(r => r.energy)) * 1.12;
    const x = i => pad.l + (iw * i) / (rows.length - 1 || 1);
    const yE = v => pad.t + ih - (v / maxE) * ih;
    const yN = v => pad.t + ih - (v / maxN) * ih;
    const barW = Math.min(18, iw / rows.length * 0.42);
    const grid = [0, 1, 2, 3, 4].map(k => {
      const y = pad.t + (ih * k) / 4, val = step * (4 - k);
      return `<line x1="${pad.l}" y1="${y}" x2="${w - pad.r}" y2="${y}" stroke="#eef2f6"/>
        <text x="${pad.l - 8}" y="${y + 4}" text-anchor="end" font-size="10" fill="#94a3b8">${val >= 1000 ? (val / 1000).toFixed(val % 1000 ? 1 : 0) + 'k' : val}</text>`;
    }).join('');
    const bars = rows.map((r, i) => `<rect x="${x(i) - barW / 2}" y="${yN(r.energy)}" width="${barW}" height="${pad.t + ih - yN(r.energy)}" rx="3" fill="#cfe3ea"><title>${r.m} 能耗 ${r.energy} tce</title></rect>`).join('');
    const pts = rows.map((r, i) => `${x(i)},${yE(r.emission)}`).join(' ');
    const dots = rows.map((r, i) => `<circle cx="${x(i)}" cy="${yE(r.emission)}" r="3.2" fill="#fff" stroke="#0f7a5a" stroke-width="2"><title>${r.m} 排放 ${r.emission} tCO₂e</title></circle>`).join('');
    const labels = rows.map((r, i) => `<text x="${x(i)}" y="${h - 8}" text-anchor="middle" font-size="10" fill="#94a3b8">${r.m}</text>`).join('');
    return `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="月度趋势">
      <defs><linearGradient id="g1" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0f7a5a" stop-opacity=".22"/><stop offset="1" stop-color="#0f7a5a" stop-opacity="0"/></linearGradient></defs>
      ${grid}${bars}<polygon points="${pad.l},${pad.t + ih} ${pts} ${w - pad.r},${pad.t + ih}" fill="url(#g1)"/>
      <polyline points="${pts}" fill="none" stroke="#0f7a5a" stroke-width="2.2" stroke-linejoin="round"/>${dots}${labels}</svg>`;
  }

  function donut(items, size = 168) {
    const total = items.reduce((s, i) => s + i.value, 0);
    const r = 62, c = 2 * Math.PI * r;
    let acc = 0;
    const arcs = items.map(it => {
      const len = (it.value / total) * c;
      const seg = `<circle cx="84" cy="84" r="${r}" fill="none" stroke="${it.color}" stroke-width="17"
        stroke-dasharray="${len - 3} ${c - len + 3}" stroke-dashoffset="${-acc}" stroke-linecap="round"><title>${it.name} ${it.value}%</title></circle>`;
      acc += len; return seg;
    }).join('');
    return `<svg viewBox="0 0 168 168" width="${size}" height="${size}" role="img" aria-label="能源结构">
      <circle cx="84" cy="84" r="${r}" fill="none" stroke="#f1f5f9" stroke-width="17"/>
      <g transform="rotate(-90 84 84)">${arcs}</g>
      <text x="84" y="80" text-anchor="middle" font-size="12" fill="#64748b">能源结构</text>
      <text x="84" y="100" text-anchor="middle" font-size="15" font-weight="700" fill="#0f172a">${items.length} 类</text></svg>`;
  }


  function niceMax(raw, parts = 3) {
    const rough = raw / parts;
    const mag = Math.pow(10, Math.floor(Math.log10(rough || 1)));
    const n = rough / mag;
    const step = (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag;
    return step * parts;
  }
  function barChart(rows, key, color = '#0f7a5a', unit = '') {
    const w = 720, h = 210, pad = { l: 44, r: 14, t: 14, b: 28 };
    const rawMax = Math.max(...rows.map(r => r[key])) * 1.06 || 1;
    const max = niceMax(rawMax, 3);
    const bw = (w - pad.l - pad.r) / rows.length;
    const y = v => pad.t + (h - pad.t - pad.b) - (v / max) * (h - pad.t - pad.b);
    return `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="柱状图">
      ${[0, 1, 2, 3].map(k => {
        const yy = pad.t + (h - pad.t - pad.b) * k / 3, v = max * (1 - k / 3);
        const lbl = max >= 1000 ? (v / 1000).toFixed(1).replace(/\.0$/, '') + 'k' : v.toFixed(0);
        return `<line x1="${pad.l}" y1="${yy}" x2="${w - pad.r}" y2="${yy}" stroke="#eef2f6"/>
          <text x="${pad.l - 7}" y="${yy + 4}" text-anchor="end" font-size="9.5" fill="#94a3b8">${lbl}</text>`;
      }).join('')}
      ${rows.map((r, i) => {
        const x = pad.l + bw * i + bw * .22, bh = (h - pad.t - pad.b) * (r[key] / max);
        return `<rect x="${x}" y="${y(r[key])}" width="${bw * .56}" height="${bh}" rx="4" fill="${color}"><title>${r.m || r.name} ${r[key]} ${unit}</title></rect>
          <text x="${x + bw * .28}" y="${h - 8}" text-anchor="middle" font-size="10" fill="#94a3b8">${r.m || r.name}</text>`;
      }).join('')}</svg>`;
  }

  function pager(total, page, size) {
    const pages = Math.max(1, Math.ceil(total / size));
    const nums = [];
    for (let i = 1; i <= pages; i++) {
      if (i === 1 || i === pages || Math.abs(i - page) <= 1) nums.push(i);
      else if (nums[nums.length - 1] !== '…') nums.push('…');
    }
    return `<div class="pager"><span>共 ${total} 条</span><span class="spacer"></span>
      <button data-go="${page - 1}" ${page <= 1 ? 'disabled' : ''} aria-label="上一页">‹</button>
      ${nums.map(n => n === '…' ? '<span class="muted">…</span>' : `<button data-go="${n}" class="${n === page ? 'active' : ''}">${n}</button>`).join('')}
      <button data-go="${page + 1}" ${page >= pages ? 'disabled' : ''} aria-label="下一页">›</button>
      <span class="small muted" style="margin-left:10px">${size} 条/页</span>
      ${pages > 1 ? `<span class="small muted" style="margin-left:10px">跳至</span>
      <input class="input pg-jump" data-jump="${pages}" value="${page}" aria-label="跳至页码" style="width:48px;height:28px;padding:0 6px;text-align:center;margin:0 4px">
      <span class="small muted">页</span>` : ''}</div>`;
  }
  function bindPager(root, onGo) {
    root.querySelectorAll('[data-go]').forEach(b => b.onclick = () => { const n = Number(b.dataset.go); if (!b.disabled && n > 0) onGo(n); });
    root.querySelectorAll('[data-jump]').forEach(inp => {
      const jump = () => {
        const max = Number(inp.dataset.jump) || 1;
        const n = Math.min(max, Math.max(1, Number(inp.value) || 1));
        if (n !== Number(inp.value)) inp.value = n;
        onGo(n);
      };
      inp.onkeydown = e => { if (e.key === 'Enter') { e.preventDefault(); jump(); } };
      inp.onblur = jump;
    });
  }

    const localMonth = (d = new Date()) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
  const routes = {};
  const route = (path, cfg) => { routes[path] = cfg; };

  /** 菜单路径 → 页面编码（与后端 PageCatalog 一致）。 */
  function pageOfPath(path) {
    const g = guardOfPath(path);
    return g ? g.page : null;
  }

  /**
   * 路径的守卫信息：页面编码 + 菜单级限制。
   * 系统管理（adminOnly）与平台管理（platform）没有页面编码，必须靠菜单标记拦住直接输入 hash。
   */
  function guardOfPath(path) {
    const menu = window.MENU || [];
    for (const g of menu) {
      if (g.type === 'page' && g.path === path) {
        return { page: g.page || null, adminOnly: false, platform: false };
      }
      for (const c of (g.children || [])) {
        if (c.path !== path) continue;
        return { page: c.page || null, adminOnly: !!g.adminOnly, platform: !!g.platform };
      }
    }
    return null;
  }
  function canSeePage(code) {
    const pages = (state && state.pages) || ['*'];
    return pages.indexOf('*') >= 0 || pages.indexOf(code) >= 0;
  }

  const openTabs = [{ path: '/home', title: '首页' }];
  const CAL_ICO = '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="3" y="5" width="18" height="16" rx="3"/><path d="M8 3v4M16 3v4M3 10h18"/></svg>';
  function rangePicker(o) {
    o = o || {};
    const mode = o.mode || 'date', from = o.from || '', to = o.to || '';
    const val = from ? from + ' ~ ' + (to || '') : '';
    return `<div class="pick rp" data-rp="${mode}" data-from="${from}" data-to="${to}">
      <input class="input" readonly value="${esc(val)}" placeholder="${esc(o.ph || (mode === 'year' ? '选择年份范围' : mode === 'month' ? '选择月份范围' : '选择日期范围'))}">
      <span class="pick-ico">${CAL_ICO}</span>
      <div class="pick-pop floating rp-pop"><div class="pick-head"><button type="button" data-rp-prev aria-label="上一页">‹</button><b class="rp-title"></b><button type="button" data-rp-next aria-label="下一页">›</button></div><div class="rp-body"></div></div>
    </div>`;
  }
  function bindRangePickers(root, onChange) {
    root.querySelectorAll('[data-rp]').forEach(box => {
      if (box.dataset.bound) return; box.dataset.bound = '1';
      const mode = box.dataset.rp, inp = box.querySelector('input'), pop = box.querySelector('.pick-pop'), body = box.querySelector('.rp-body'), title = box.querySelector('.rp-title');
      let from = box.dataset.from || '', to = box.dataset.to || '', first = true;
      const seed = from || to || '';
      const now = new Date();
      let vy = seed ? Number(seed.slice(0, 4)) : now.getFullYear(), vm = seed && seed.length > 7 ? Number(seed.slice(5, 7)) - 1 : now.getMonth();
      const pad = n => String(n).padStart(2, '0');
      const clsOf = v => [from && to && v >= from && v <= to ? 'in' : '', v === from ? 'start' : '', v === to ? 'end' : ''].join(' ').trim();
      const paint = () => {
        if (mode === 'year') {
          const ds = vy - vy % 10;
          title.textContent = ds + ' – ' + (ds + 9);
          body.className = 'rp-body pick-grid years';
          body.innerHTML = Array.from({ length: 10 }, (_, i) => { const v = String(ds + i); return `<button type="button" data-v="${v}" class="${clsOf(v)}">${v}</button>`; }).join('');
        } else if (mode === 'month') {
          title.textContent = vy + ' 年';
          body.className = 'rp-body pick-grid months';
          body.innerHTML = Array.from({ length: 12 }, (_, i) => { const v = vy + '-' + pad(i + 1); return `<button type="button" data-v="${v}" class="${clsOf(v)}">${i + 1}月</button>`; }).join('');
        } else {
          title.textContent = vy + ' 年 ' + pad(vm + 1) + ' 月';
          const start = new Date(vy, vm, 1).getDay(), days = new Date(vy, vm + 1, 0).getDate();
          body.className = 'rp-body';
          body.innerHTML = '<div class="rp-week">' + ['日','一','二','三','四','五','六'].map(x => `<span>${x}</span>`).join('') + '</div><div class="rp-grid">'
            + Array.from({ length: start }, () => '<i></i>').join('')
            + Array.from({ length: days }, (_, i) => { const v = vy + '-' + pad(vm + 1) + '-' + pad(i + 1); return `<button type="button" data-v="${v}" class="${clsOf(v)}">${i + 1}</button>`; }).join('') + '</div>';
        }
        body.querySelectorAll('[data-v]').forEach(b => b.onclick = ev => {
          ev.stopPropagation();
          const v = b.dataset.v;
          if (first) { from = v; to = ''; first = false; inp.value = from + ' ~ '; box.dataset.from = from; delete box.dataset.to; }
          else {
            if (v < from) { to = from; from = v; } else to = v;
            first = true; inp.value = from + ' ~ ' + to; box.dataset.from = from; box.dataset.to = to;
            pop.classList.remove('open'); onChange && onChange(from, to);
          }
          paint();
        });
      };
      inp.onclick = () => { if (!pop.classList.contains('open')) { first = true; paint(); pop.classList.add('open'); } else pop.classList.remove('open'); };
      box.querySelector('[data-rp-prev]').onclick = ev => { ev.stopPropagation(); if (mode === 'year') vy -= 10; else if (mode === 'month') vy--; else { vm--; if (vm < 0) { vm = 11; vy--; } } paint(); };
      box.querySelector('[data-rp-next]').onclick = ev => { ev.stopPropagation(); if (mode === 'year') vy += 10; else if (mode === 'month') vy++; else { vm++; if (vm > 11) { vm = 0; vy++; } } paint(); };
      root.addEventListener('click', e => { if (!e.target.closest('[data-rp]')) pop.classList.remove('open'); });
    });
  }

  const CHEV = '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="m6 9 6 6 6-6"/></svg>';
  function selectMenu(o) {
    o = o || {};
    const raw = o.options || [];
    // 支持两种入参：字符串（label=value，'全部' 取值空）与 { label, value }
    const opts = raw.map(x => typeof x === 'object' ? { label: String(x.label), value: String(x.value) } : { label: String(x), value: x === '全部' ? '' : String(x) });
    const cur = (o.value != null ? opts.find(x => x.value === String(o.value)) : null) || opts[0] || { label: '全部', value: '' };
    return `<div class="sel" data-sel>
      <button type="button" class="sel-trigger" aria-haspopup="listbox"><span>${esc(cur.label)}</span>${CHEV}</button>
      <div class="sel-pop" role="listbox">${opts.map(x => `<button type="button" role="option" data-v="${esc(x.value)}" data-l="${esc(x.label)}" class="${x.value === cur.value ? 'on' : ''}">${esc(x.label)}<i>✓</i></button>`).join('')}</div>
      <input type="hidden"${o.id ? ` id="${esc(o.id)}"` : ''} data-filter="${esc(o.key || '')}" value="${esc(cur.value)}">
    </div>`;
  }
  function bindSelects(root, onChange) {
    root.querySelectorAll('[data-sel]').forEach(box => {
      if (box.dataset.bound) return; box.dataset.bound = '1';
      const trig = box.querySelector('.sel-trigger'), pop = box.querySelector('.sel-pop'), inp = box.querySelector('input'), lab = trig.querySelector('span');
      trig.onclick = e => {
        e.stopPropagation();
        const open = pop.classList.contains('open');
        root.querySelectorAll('[data-sel] .sel-pop.open').forEach(x => { x.classList.remove('open'); x.parentElement.querySelector('.sel-trigger').classList.remove('open'); });
        if (!open) { pop.classList.add('open'); trig.classList.add('open'); }
      };
      pop.querySelectorAll('[data-v]').forEach(o => o.onclick = () => {
        inp.value = o.dataset.v;
        lab.textContent = o.dataset.l || o.dataset.v;
        pop.querySelectorAll('[data-v]').forEach(x => x.classList.remove('on'));
        o.classList.add('on');
        pop.classList.remove('open'); trig.classList.remove('open');
        onChange && onChange(inp.value);
      });
      root.addEventListener('click', e => { if (!e.target.closest('[data-sel]')) { pop.classList.remove('open'); trig.classList.remove('open'); } });
    });
  }
  function resetSelects(root) {
    root.querySelectorAll('[data-sel]').forEach(box => {
      const inp = box.querySelector('input'), lab = box.querySelector('.sel-trigger span');
      const first = box.querySelector('[data-v]');
      inp.value = first ? first.dataset.v : '';
      lab.textContent = first ? (first.dataset.l || first.dataset.v || '全部') : '全部';
      box.querySelectorAll('[data-v]').forEach((x, i) => x.classList.toggle('on', i === 0));
    });
  }

  /** 选项支持字符串或 {value,label}：标签与原值不同的下拉必须传对象，否则会丢 value。 */
  function formSelect(name, opts, val, disabled) {
    const list = (opts || [])
      .map(x => (x && typeof x === 'object')
        ? { value: String(x.value == null ? '' : x.value), label: String(x.label == null ? x.value : x.label) }
        : { value: String(x), label: String(x) })
      .filter(x => x.value !== '');
    const cur = val == null || val === '' ? null : list.find(x => x.value === String(val));
    return `<div class="sel fsel" data-fsel>
      <button type="button" class="sel-trigger" ${disabled ? 'disabled' : ''}><span>${cur ? esc(cur.label) : '请选择'}</span>${CHEV}</button>
      <div class="sel-pop">${list.map(x => `<button type="button" data-v="${esc(x.value)}" class="${cur && x.value === cur.value ? 'on' : ''}">${esc(x.label)}<i>✓</i></button>`).join('')}</div>
      <input type="hidden" name="${esc(name || '')}" value="${esc(cur ? cur.value : '')}">
    </div>`;
  }
  function formDate(name, val, disabled) {
    return `<div class="pick fdate" data-fdate>
      <input class="input" name="${esc(name)}" value="${esc(val || '')}" placeholder="选择日期" readonly ${disabled ? 'disabled' : ''}>
      <span class="pick-ico">${CAL_ICO}</span>
      <div class="pick-pop fdate-pop"><div class="pick-head"><button type="button" data-fd-prev aria-label="上个月">‹</button><b></b><button type="button" data-fd-next aria-label="下个月">›</button></div><div class="rp-body"></div></div>
    </div>`;
  }
  function bindFormWidgets(scope) {
    scope.querySelectorAll('[data-fsel]').forEach(box => {
      if (box.dataset.bound) return; box.dataset.bound = '1';
      const trig = box.querySelector('.sel-trigger'), pop = box.querySelector('.sel-pop'), inp = box.querySelector('input'), lab = trig.querySelector('span');
      if (trig.disabled) return;
      trig.onclick = e => { e.stopPropagation(); pop.classList.toggle('open'); trig.classList.toggle('open'); };
      pop.querySelectorAll('[data-v]').forEach(o => o.onclick = ev => {
        ev.stopPropagation();
        inp.value = o.dataset.v;
        lab.textContent = (o.textContent || o.dataset.v).replace('✓', '').trim();
        const f0 = box.closest('.field'); if (f0) { f0.classList.remove('error'); const em = f0.querySelector('.err-msg'); if (em) em.textContent = ''; }
        pop.querySelectorAll('[data-v]').forEach(x => x.classList.remove('on')); o.classList.add('on');
        pop.classList.remove('open'); trig.classList.remove('open');
        inp.dispatchEvent(new Event('input', { bubbles: true }));
      });
      scope.addEventListener('click', e => { if (!e.target.closest('[data-fsel]')) { pop.classList.remove('open'); trig.classList.remove('open'); } });
    });
    scope.querySelectorAll('[data-fdate]').forEach(box => {
      if (box.dataset.bound) return; box.dataset.bound = '1';
      const inp = box.querySelector('input'), pop = box.querySelector('.pick-pop'), body = box.querySelector('.rp-body'), title = pop.querySelector('.pick-head b');
      if (inp.disabled) return;
      const seed = inp.value || '';
      const now = new Date();
      let vy = seed ? Number(seed.slice(0, 4)) : now.getFullYear(), vm = seed ? Number(seed.slice(5, 7)) - 1 : now.getMonth();
      const pad = n => String(n).padStart(2, '0');
      const paint = () => {
        title.textContent = vy + ' 年 ' + pad(vm + 1) + ' 月';
        const start = new Date(vy, vm, 1).getDay(), days = new Date(vy, vm + 1, 0).getDate();
        body.innerHTML = '<div class="rp-week">' + ['日','一','二','三','四','五','六'].map(x => `<span>${x}</span>`).join('') + '</div><div class="rp-grid">'
          + Array.from({ length: start }, () => '<i></i>').join('')
          + Array.from({ length: days }, (_, i) => { const v = vy + '-' + pad(vm + 1) + '-' + pad(i + 1); return `<button type="button" data-d="${v}" class="${inp.value === v ? 'on' : ''}">${i + 1}</button>`; }).join('') + '</div>';
        body.querySelectorAll('[data-d]').forEach(b => b.onclick = ev => {
          ev.stopPropagation(); inp.value = b.dataset.d; pop.classList.remove('open'); inp.dispatchEvent(new Event('input', { bubbles: true }));
          const f0 = box.closest('.field'); if (f0) { f0.classList.remove('error'); const em = f0.querySelector('.err-msg'); if (em) em.textContent = ''; }
        });
      };
      inp.onclick = () => { if (!pop.classList.contains('open')) { paint(); pop.classList.add('open'); } else pop.classList.remove('open'); };
      pop.querySelector('[data-fd-prev]').onclick = ev => { ev.stopPropagation(); vm--; if (vm < 0) { vm = 11; vy--; } paint(); };
      pop.querySelector('[data-fd-next]').onclick = ev => { ev.stopPropagation(); vm++; if (vm > 11) { vm = 0; vy++; } paint(); };
      scope.addEventListener('click', e => { if (!e.target.closest('[data-fdate]')) pop.classList.remove('open'); });
    });
  }
  function formMonth(name, val) {
    return `<div class="pick" data-mpick>
      <input class="input" name="${esc(name)}" value="${esc(val || '')}" placeholder="选择月份" readonly>
      <span class="pick-ico">${CAL_ICO}</span>
      <div class="pick-pop"><div class="pick-head"><button type="button" data-my="-1" aria-label="上一年">‹</button><b></b><button type="button" data-my="1" aria-label="下一年">›</button></div><div class="pick-grid months"></div></div>
    </div>`;
  }
  function bindMonthPickers(scope) {
    scope.querySelectorAll('[data-mpick]').forEach(box => {
      if (box.dataset.bound) return; box.dataset.bound = '1';
      const inp = box.querySelector('input'), pop = box.querySelector('.pick-pop'), head = box.querySelector('.pick-head b'), grid = box.querySelector('.pick-grid');
      if (inp.disabled) return;
      const nowYm = localMonth();
      let y = Number((inp.value || nowYm).slice(0, 4));
      const pad = n => String(n).padStart(2, '0');
      const paint = () => {
        head.textContent = y + ' 年';
        grid.innerHTML = Array.from({ length: 12 }, (_, i) => { const m = pad(i + 1); return `<button type="button" data-m="${m}" class="${inp.value === y + '-' + m ? 'on' : ''}">${i + 1}月</button>`; }).join('');
        grid.querySelectorAll('[data-m]').forEach(b => b.onclick = ev => {
          ev.stopPropagation();
          inp.value = y + '-' + b.dataset.m; pop.classList.remove('open');
          inp.dispatchEvent(new Event('input', { bubbles: true }));
          const f0 = box.closest('.field'); if (f0) { f0.classList.remove('error'); const em = f0.querySelector('.err-msg'); if (em) em.textContent = ''; }
        });
      };
      inp.onclick = () => { if (!pop.classList.contains('open')) { paint(); pop.classList.add('open'); } else pop.classList.remove('open'); };
      box.querySelectorAll('[data-my]').forEach(b => b.onclick = ev => { ev.stopPropagation(); y += Number(b.dataset.my); paint(); });
      scope.addEventListener('click', e => { if (!e.target.closest('[data-mpick]')) pop.classList.remove('open'); });
    });
  }
  /**
   * 把原生 select/date 换成统一样式控件。
   * 原生元素保留在 DOM（隐藏）并双向同步：老代码按 id 读写 .value、绑 onchange 仍然有效；
   * 选项会被动态重填的下拉加 data-native，不做替换。
   */
  function enhanceForm(scope) {
    scope.querySelectorAll('select.select').forEach(sel => {
      if (sel.dataset.enhanced) return; sel.dataset.enhanced = '1';
      if (sel.hasAttribute('data-native')) return;
      const opts = [...sel.options].map(o => ({ value: o.value, label: o.text.trim() }));
      const box = document.createElement('div');
      box.innerHTML = formSelect(sel.name, opts, sel.value, sel.disabled);
      const w = box.firstElementChild;
      sel.after(w);
      sel.style.display = 'none';
      const hidden = w.querySelector('input');
      hidden.addEventListener('input', () => {
        if (sel.value === hidden.value) return;
        sel.value = hidden.value;
        sel.dispatchEvent(new Event('change', { bubbles: true }));
      });
    });
    scope.querySelectorAll('input[type="date"].input').forEach(inp => {
      if (inp.dataset.enhanced) return; inp.dataset.enhanced = '1';
      const box = document.createElement('div');
      box.innerHTML = formDate(inp.name, inp.value, inp.disabled || inp.readOnly);
      const w = box.firstElementChild;
      inp.after(w);
      inp.style.display = 'none';
      const hidden = w.querySelector('input');
      hidden.addEventListener('input', () => {
        if (inp.value === hidden.value) return;
        inp.value = hidden.value;
        inp.dispatchEvent(new Event('change', { bubbles: true }));
      });
    });
    bindFormWidgets(scope);
  }

  function pdfPreview(title, url) {
    modal({
      title: title || '报告预览', width: 900,
      body: `<div class="pdf-frame"><iframe src="${esc(url)}" title="PDF 预览"></iframe></div>`,
      foot: `<a class="btn" href="${esc(url)}" target="_blank" rel="noopener">新窗口打开</a><button class="btn btn-primary" data-close>关闭</button>`
    });
  }

  function renderTags(active, title) {
    if (!openTabs.find(t => t.path === active)) openTabs.push({ path: active, title });
    const host = document.getElementById('tags');
    if (!host) return;
    host.innerHTML = openTabs.map(t => `<span class="tag-item ${t.path === active ? 'active' : ''}" data-path="${esc(t.path)}">${esc(t.title)}${t.path === '/home' ? '' : `<b data-close="${esc(t.path)}" aria-label="关闭">×</b>`}</span>`).join('')
      + `<button class="tag-more" id="tag-more" aria-label="页签操作" title="页签操作">▾</button>`
      + `<div class="tag-menu" id="tag-menu">
          <button data-act="close">关闭当前</button>
          <button data-act="others">关闭其他</button>
          <button data-act="all">关闭全部</button>
        </div>`;
    host.querySelectorAll('.tag-item').forEach(el => el.onclick = (e) => {
      if (e.target.dataset.close) return;
      location.hash = el.dataset.path;
    });
    host.querySelectorAll('[data-close]').forEach(b => b.onclick = (e) => {
      e.stopPropagation();
      const p = b.dataset.close;
      const i = openTabs.findIndex(t => t.path === p);
      if (i >= 0) openTabs.splice(i, 1);
      if (p === active) location.hash = openTabs[Math.max(0, i - 1)].path;
      else renderTags(active, title);
    });
    const more = host.querySelector('#tag-more'), menu = host.querySelector('#tag-menu');
    if (more) more.onclick = (e) => { e.stopPropagation(); menu.classList.toggle('open'); };
    if (menu) menu.querySelectorAll('[data-act]').forEach(b => b.onclick = () => {
      const act = b.dataset.act;
      if (act === 'close') {
        const i = openTabs.findIndex(t => t.path === active);
        if (i >= 0 && openTabs[i].path !== '/home') openTabs.splice(i, 1);
      } else if (act === 'others') {
        const keep = openTabs.filter(t => t.path === '/home' || t.path === active);
        openTabs.length = 0; keep.forEach(t => openTabs.push(t));
      } else if (act === 'all') {
        openTabs.length = 0; openTabs.push({ path: '/home', title: '首页' });
      }
      menu.classList.remove('open');
      const target = openTabs.find(t => t.path === active) ? active : openTabs[openTabs.length - 1].path;
      if (target === active) renderTags(active, title);
      else location.hash = target;
    });
  }
  document.addEventListener('click', (e) => {
    if (e.target.closest('.tag-menu') || e.target.closest('.tag-more')) return;
    document.querySelectorAll('.tag-menu.open').forEach(m => m.classList.remove('open'));
  });

  const go = (p) => { location.hash = p; };

  function applyRole(scope) {
    if (!scope) return;
    if (state.role !== 'admin') {
      scope.querySelectorAll('[data-del],[data-fp-del],[data-d-del],[data-q-del],[data-del-rule],#ac-bulk,#g-bulk,#q-bulk,#w-bulk,#rec-bulk,#fp-bulk,#d-bulk')
        .forEach(el => el.remove());
    }
    scope.querySelectorAll('button').forEach(b => {
      const id = b.id || '';
      if (/export/i.test(id) || b.dataset.dl) {
        b.addEventListener('click', (e) => {
          if (!can('export')) { e.preventDefault(); e.stopImmediatePropagation(); toast('err', '对不起，您没有权限访问此内容哦~'); }
        }, true);
      }
      if (id === 'r-gen' || id === 'gretry') {
        b.addEventListener('click', (e) => {
          if (!can('report')) { e.preventDefault(); e.stopImmediatePropagation(); toast('err', '对不起，您没有权限访问此内容哦~'); }
        }, true);
      }
    });
  }

  async function render() {
    const h = (location.hash || '#/home').replace(/^#/, '');
    const path = h.split('?')[0] || '/home';
    const registered = !!routes[path];
    const cfg = routes[path];
    // 未知路由：渲染 404 页，而不是静默回退首页（BUG-003）。
    // 历史行为会把非法 hash 渲染成首页内容，用户以为链接正常，排障困难。
    // 这里保留 hash 不改写（便于定位错误链接），且不把非法路径写进 openTabs。
    // window.__pagesReady 由 index.html 在全部页面模块加载完成后置 true，
    // 加载竞态期间（路由尚未注册完）仍按旧逻辑直接返回，避免把合法路由误判成 404。
    if (!cfg && window.__pagesReady) {
      state.route = path;
      $$('.nav a').forEach(a => a.classList.remove('active'));
      $('#crumb-title').textContent = '页面不存在';
      $('#crumb-group').textContent = '';
      document.title = '404 · 能碳管理平台';
      if (window.innerWidth <= 900) $('#side').classList.remove('open');
      $('#view').innerHTML = `<div class="page"><section class="card"><div class="card-body">
        <div class="tb-empty">${icon('lock')}<div>页面不存在（404）</div>
        <div class="small">链接可能已失效，请从左侧菜单重新进入</div>
        <button class="btn btn-primary" style="margin-top:12px" onclick="location.hash='#/home'">返回首页</button>
        </div></div></section></div>`;
      window.scrollTo(0, 0);
      return;
    }
    if (!cfg) return; // 页面模块尚未加载完成（例如登录后立即跳转）
    state.route = path;

    $$('.nav a').forEach(a => a.classList.toggle('active', a.getAttribute('href') === '#' + path));
    // 展开所属分组
    const active = document.querySelector('.nav a.active');
    if (active && active.closest('.nav-group')) active.closest('.nav-group').classList.remove('collapsed');
    $('#crumb-title').textContent = cfg.title;
    $('#crumb-group').textContent = cfg.group || '';
    document.title = cfg.title + ' · 能碳管理平台';
    renderTags(path, cfg.title);
    document.body.classList.toggle('dense', state.density === 'compact');
    if (window.innerWidth <= 900) $('#side').classList.remove('open');

    const view = $('#view');
    // 页面级守卫：没有该页面权限时直接 403，不只藏菜单；
    // 系统管理与平台管理没有页面编码，按菜单标记（adminOnly / platform）拦截；
    // 已注册但未纳入菜单的路由没有页面编码，等同未授权入口，防止绕过页面权限。
    const guard = guardOfPath(path);
    const denied = (registered && !guard) || (!!guard && (
      (guard.page && !canSeePage(guard.page))
      || (guard.adminOnly && state.role !== 'admin')
      || (guard.platform && !state.platformAdmin)));
    if (denied) {
      $('#crumb-title').textContent = '无权访问';
      $('#crumb-group').textContent = '';
      view.innerHTML = `<div class="page"><section class="card"><div class="card-body">
        <div class="tb-empty">${icon('lock')}<div>没有该功能的访问权限</div>
        <div class="small">请联系租户管理员调整角色页面，或返回${state.platformAdmin ? '平台概览' : '首页'}</div>
        <button class="btn btn-primary" style="margin-top:12px" onclick="location.hash='${state.platformAdmin ? '#/platform/overview' : '#/home'}'">返回${state.platformAdmin ? '平台概览' : '首页'}</button>
        </div></div></section></div>`;
      return;
    }
    view.innerHTML = `<div class="page"><div class="skel" style="height:26px;width:200px;margin-bottom:14px"></div>
      <div class="grid grid-4">${Array(4).fill('<div class="skel" style="height:96px"></div>').join('')}</div>
      <div class="skel" style="height:260px;margin-top:12px"></div></div>`;
    await sleep(160);
    view.innerHTML = cfg.render();
    cfg.mount && cfg.mount(view);
    applyRole(view);
    window.scrollTo(0, 0);
  }

  window.addEventListener('hashchange', () => {
    document.querySelectorAll('.overlay,.drawer,.drawer-mask').forEach(n => n.remove());
    document.body.style.overflow = '';
    render();
  });

  window.UI = { $, $$, esc, num, int, icon, toast, modal, confirm, drawer, busy, sleep, trendChart, donut, barChart, pager, bindPager, route, go, render, state, routes, can, rangePicker, bindRangePickers, selectMenu, bindSelects, resetSelects, formSelect, formDate, formMonth, bindMonthPickers, enhanceForm, pdfPreview };
})();
