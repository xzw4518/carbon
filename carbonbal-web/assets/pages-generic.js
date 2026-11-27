/* 通用页面：台账（列表+表单）与分析（筛选+图表+明细） */
(function () {
  const { $, $$, esc, num, int, icon, toast, modal, confirm, busy, sleep, trendChart, donut, barChart, pager, bindPager, route } = UI;
  const localDate = (d = new Date()) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  const localMonth = (d = new Date()) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');

  const CAL_ICO = '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="3" y="5" width="18" height="16" rx="3"/><path d="M8 3v4M16 3v4M3 10h18"/></svg>';
  const monthRange = (back = 1) => {
    const now = new Date(); const from = new Date(now.getFullYear(), now.getMonth() - back, 1);
    const fmt = (d) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
    return { from: fmt(from), to: fmt(now) };
  };
  const pick = (k, f, d = '—') => {
    const a = DB[k] || [];
    const v = a.length ? f(a[0]) : null;
    return v === null || v === undefined || v === '' ? d : v;
  };

  /* ================= 台账工厂 ================= */
  const dayStr = v => (v ? String(v).slice(0, 10) : '');
  const timeStr = v => (v ? String(v).replace('T', ' ').slice(0, 16) : '');
  const validStr = (a, b) => (a ? dayStr(a) + ' ~ ' + (b ? dayStr(b) : '') : '');
  const recId = (r, pre) => Object.assign({}, r, { dbId: r.id, id: pre + r.id });
  const rangeOf = (s, k) => s.filters[k] || {};

  const SERVER_PAGES = {
    '产品列表': { api: '/products', query: s => ({ keyword: s.kw, type: s.filters.type }), map: r => recId(r, 'PD-') },
    '产品产值': { api: '/production-values', query: s => ({ keyword: s.kw, from: rangeOf(s, 'period').from, to: rangeOf(s, 'period').to }), map: r => Object.assign(recId(r, 'PV-'), { product: r.productName, value: r.outputValue }) },
    '生产总值': { api: '/total-values', query: s => ({ keyword: s.kw, from: rangeOf(s, 'period').from, to: rangeOf(s, 'period').to }), map: r => Object.assign(recId(r, 'TV-'), { value: r.outputValue }) },
    '碳配额资产管理': { api: '/carbon-assets', query: s => ({ keyword: s.kw, type: s.filters.type, year: s.filters.year }), map: r => Object.assign(recId(r, 'AS-'), { year: r.assetYear }) },
    '信用资产管理': { api: '/carbon-assets', query: s => ({ keyword: s.kw, year: s.filters.year, type: '信用资产' }), map: r => Object.assign(recId(r, 'CC-'), { year: r.assetYear, status: r.status || '已备案' }) },
    '交易管理': { api: '/carbon-trades', query: s => ({ assetType: s.filters.assetType, type: s.filters.type, from: rangeOf(s, 'date').from, to: rangeOf(s, 'date').to }), map: r => Object.assign(recId(r, 'TR-'), { asset: r.assetName, use: r.usePurpose, date: dayStr(r.tradeDate) }) },
    '原始凭证管理': { api: '/evidences', query: s => ({ keyword: s.kw }), map: r => Object.assign(recId(r, 'EV-'), { desc: r.description, valid: validStr(r.validFrom, r.validTo), fileIds: (r.files || []).map(f => f.id), files: (r.files || []).map(f => f.name) }) },
    '第三方报告管理': { api: '/third-party-reports', query: s => ({ keyword: s.kw }), map: r => Object.assign(recId(r, 'TP-'), { desc: r.description, valid: validStr(r.validFrom, r.validTo), fileId: r.file ? r.file.id : null }) },
    '设施管理': { api: '/facilities', query: s => ({ keyword: s.kw }), map: r => Object.assign(recId(r, 'FA-'), { parent: r.parentName || '—', parentId: r.parentId }) },
    '设备数据': { api: '/device-readings/page', query: s => ({ keyword: s.kw, status: s.filters.status, from: rangeOf(s, 'date').from ? rangeOf(s, 'date').from + 'T00:00:00' : '', to: rangeOf(s, 'date').to ? rangeOf(s, 'date').to + 'T23:59:59' : '' }), map: r => Object.assign(recId(r, 'DD-'), { device: r.deviceName, date: timeStr(r.ts) }) },
    '事件预警': { api: '/alert-events/page', query: s => ({ keyword: s.kw, type: s.filters.type }), map: r => recId(r, 'WE-') },
    '节能改造项目': { api: '/retrofits', query: s => ({ keyword: s.kw, year: s.filters.year }), map: r => Object.assign(recId(r, 'RP-'), { year: r.projectYear, at: dayStr(r.atDate) }) },
    '能源管理情况': { api: '/energy-mgmt', query: s => ({ keyword: s.kw || s.filters.org }), map: r => Object.assign(recId(r, 'EM-'), { at: dayStr(r.atDate), org: r.orgName, record: r.filing, system: r.systemName }) },
    '供应链碳管理': { api: '/suppliers', query: s => ({ keyword: s.kw || s.filters.product }), map: r => Object.assign(recId(r, 'SP-'), { date: dayStr(r.date) }) }
  };

  const IMPORT_RES = {
    '产品列表': 'products',
    '产品产值': 'production-values',
    '碳配额资产管理': 'carbon-assets',
    '信用资产管理': 'carbon-assets',
    '供应链碳管理': 'suppliers',
    '设施管理': 'facilities'
  };

  function listPage(cfg) {
    const sp = SERVER_PAGES[cfg.title] || {};
    const api = cfg.api || sp.api || null;
    const buildQuery = cfg.query || sp.query;
    const mapRow = cfg.map || sp.map;
    const server = !!api;
    const firstCol = (cfg.columns || [])[0] || {};
    const rowsFn = typeof cfg.rows === 'function' ? cfg.rows : null;
    const dataKey = (server || rowsFn) ? null : Object.keys(window.DB || {}).find(k => Array.isArray(DB[k]) && DB[k] === cfg.rows);
    let source = cfg.rows;
    const st = { page: 1, size: cfg.size || 10, sort: { k: cfg.sortKey || firstCol.k, dir: ['num','int'].includes(firstCol.type) ? 'desc' : 'asc' }, kw: '', filters: {}, sel: new Set(), collapsed: new Set(), rows: server ? [] : (rowsFn ? rowsFn() : cfg.rows.slice()), total: 0, loading: false, loaded: false, seq: 0 };

    const loadPage = async () => {
      if (!server) return;
      const seq = ++st.seq;
      st.loading = true;
      const qs = new URLSearchParams({ page: String(st.page), size: String(st.size) });
      const q = buildQuery ? buildQuery(st) : {};
      Object.keys(q).forEach(k => { const v = q[k]; if (v !== undefined && v !== null && v !== '') qs.set(k, v); });
      try {
        const r = await CarbonAPI.request('GET', api + '?' + qs.toString());
        if (seq !== st.seq) return;
        st.rows = (r.rows || []).map(mapRow || (x => x));
        st.total = r.total || 0;
        st.loaded = true;
        if (st.page > 1 && !st.rows.length && st.total > 0) { st.page -= 1; return loadPage(); }
      } catch (e) { toast('err', e.message); }
      finally { if (seq === st.seq) st.loading = false; }
    };
    const totalCount = () => (server ? st.total : filtered().length);
    const pageRows = () => (server ? st.rows : filtered().slice((st.page - 1) * st.size, st.page * st.size));

    const filtered = () => {
      if (rowsFn) st.rows = rowsFn();
      else if (dataKey && Array.isArray(DB[dataKey]) && DB[dataKey] !== source) {
        source = DB[dataKey]; st.rows = source.slice(); st.sel.clear(); st.page = 1;
      }
      let rows = st.rows.slice();
      if (st.kw) { const q = st.kw.toLowerCase(); rows = rows.filter(r => (cfg.kwFields || ['name']).some(k => String(r[k] ?? '').toLowerCase().includes(q))); }
      Object.entries(st.filters).forEach(([k, v]) => {
        if (!v || v === '全部') return;
        if (typeof v === 'object') {
          const conf = (cfg.filters || []).find(x => x.key === k) || {};
          const field = conf.field || k;
          const mode = conf.type === 'monthRange' ? 7 : 10;
          rows = rows.filter(r => {
            const cur = String(r[field] ?? '').slice(0, mode);
            if (v.from && cur < v.from) return false;
            if (v.to && cur > v.to) return false;
            return true;
          });
          return;
        }
        rows = rows.filter(r => String(r[k] ?? '').includes(v));
      });
      if (cfg.filterValue) rows = rows.filter(cfg.filterValue);
      // 树模式：命中子节点时保留其祖先链，避免筛选后看不到层级
      if (cfg.tree && rows.length && rows.length < source.length) {
        const hit = new Set(rows.map(r => r.dbId));
        let changed = true;
        while (changed) {
          changed = false;
          source.forEach(r => {
            if (hit.has(r.dbId) && r.parentId != null && !hit.has(r.parentId)) { hit.add(r.parentId); changed = true; }
          });
        }
        rows = source.filter(r => hit.has(r.dbId));
      }
      if (st.sort.k) rows.sort((a, b) => {
        const va = a[st.sort.k], vb = b[st.sort.k];
        const c = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va ?? '').localeCompare(String(vb ?? ''));
        return st.sort.dir === 'asc' ? c : -c;
      });
      return rows;
    };

    const cellHTML = (r, c) => {
      const v = r[c.k];
      if (c.render) return c.render(r);
      if (c.type === 'num') return `<span class="num">${typeof v === 'number' ? num(v, c.d ?? 1) : esc(v ?? '—')}</span>`;
      if (c.type === 'int') return `<span class="num">${typeof v === 'number' ? int(v) : esc(v ?? '—')}</span>`;
      if (c.type === 'tag') return `<span class="tag ${(c.map && c.map[v]) || 'tag-muted'} dot">${esc(v ?? '—')}</span>`;
      if (c.type === 'bool') return v ? '<span class="tag tag-ok dot">启用</span>' : '<span class="tag tag-muted dot">停用</span>';
      if (c.type === 'main') return `<b>${esc(v)}</b>${c.sub ? `<div class="small muted">${esc(r[c.sub] ?? '')}</div>` : ''}`;
      return esc(v ?? '—');
    };

    /** 树模式：按 parentId 组树并展开可见行（折叠节点的后代跳过）。 */
    function treeVisible() {
      const list = st.rows;
      const ids = new Set(list.map(r => r.dbId));
      const byParent = new Map();
      list.forEach(r => {
        const p = r.parentId != null && ids.has(r.parentId) ? r.parentId : 0;
        if (!byParent.has(p)) byParent.set(p, []);
        byParent.get(p).push(r);
      });
      const out = [];
      const walk = (arr, depth) => arr.forEach(r => {
        const kids = byParent.get(r.dbId) || [];
        out.push(Object.assign({}, r, { __depth: depth, __kids: kids.length }));
        if (kids.length && !st.collapsed.has(r.dbId)) walk(kids, depth + 1);
      });
      walk(byParent.get(0) || [], 0);
      return out;
    }

    function treeCell(r, c) {
      const pad = 6 + (r.__depth || 0) * 18;
      const caret = r.__kids
        ? `<span class="caret" data-caret="${r.dbId}" style="display:inline-block;width:14px;cursor:pointer;color:#94a3b8">${st.collapsed.has(r.dbId) ? '▸' : '▾'}</span>`
        : '<span style="display:inline-block;width:14px"></span>';
      const body = c.type === 'main'
        ? `<b>${esc(r[c.k])}</b>${c.sub ? `<div class="small muted">${esc(r[c.sub] ?? '')}</div>` : ''}`
        : esc(r[c.k] ?? '—');
      return `<span style="display:inline-block;padding-left:${pad}px">${caret}${body}</span>`;
    }

    function table() {
      const rows = cfg.tree ? treeVisible() : pageRows();
      const allChecked = rows.length > 0 && rows.every(r => st.sel.has(r.id));
      const acts = (cfg.actions || ['edit', 'delete']).filter(a => {
        const api = window.CarbonAPI || {};
        if (a === 'edit' || a === 'addChild' || a === 'disable' || a === 'toggle') return api.canWrite && api.canWrite();
        if (a === 'delete') return api.isAdmin && api.isAdmin();
        return true;
      });
      const colCount = cfg.columns.length + (cfg.bulk === false ? 0 : 1) + 1;
      return `<div class="table-wrap"><table class="tb"><thead><tr>
        ${cfg.bulk === false ? '' : `<th style="width:34px"><input type="checkbox" data-all ${allChecked ? 'checked' : ''} aria-label="全选"></th>`}
        ${cfg.columns.map(c => `<th style="${c.w ? 'width:' + c.w + 'px' : ''}">${esc(c.t)}</th>`).join('')}
        <th style="width:${acts.includes('download') || acts.length > 2 ? 160 : 110}px">操作</th></tr></thead>
        <tbody>${rows.length ? rows.map(r => `<tr class="${st.sel.has(r.id) ? 'selected' : ''}">
          ${cfg.bulk === false ? '' : `<td><input type="checkbox" data-check="${r.id}" ${st.sel.has(r.id) ? 'checked' : ''}></td>`}
          ${cfg.columns.map((c, ci) => `<td class="${c.type === 'num' || c.type === 'int' ? 'num' : ''}">${cfg.tree && ci === 0 ? treeCell(r, c) : cellHTML(r, c)}</td>`).join('')}
          <td class="actions">${acts.map(a =>
            a === 'edit' ? `<button class="btn btn-sm" data-edit="${r.id}">编辑</button>`
            : a === 'delete' ? `<button class="btn btn-sm btn-ghost" data-del="${r.id}">删除</button>`
            : a === 'disable' ? `<button class="btn btn-sm btn-ghost" data-disable="${r.id}">${r.disabled ? '启用' : '禁用'}</button>`
            : a === 'download' ? `<button class="btn btn-sm btn-ghost" data-dl="${r.id}">下载</button>`
            : a === 'toggle' ? `<button class="btn btn-sm btn-ghost" data-toggle="${r.id}">${r.enabled ? '禁用' : '启用'}</button>`
            : a === 'view' ? `<button class="btn btn-sm btn-ghost" data-view="${r.id}">查看</button>`
            : a === 'addChild' ? `<button class="btn btn-sm btn-ghost" data-child="${r.id}">${cfg.childText || '添加下级'}</button>` : '').join('')}</td>
        </tr>`).join('') : `<tr><td colspan="${colCount}"><div class="tb-empty">${icon('file')}<div>${server && !st.loaded ? '加载中…' : '暂无数据'}</div><div class="small">${server && !st.loaded ? '正在读取服务端数据' : '可点击右上角新建'}</div></div></td></tr>`}</tbody></table></div>
        ${cfg.tree ? '' : pager(totalCount(), st.page, st.size)}`;
    }

    function syncBulk() {
      const n = st.sel.size, b = $('#g-bulk');
      if (b) { b.disabled = n === 0; b.textContent = '批量删除' + (n ? `（${n}）` : ''); }
      const tip = $('#g-sel'); if (tip) tip.textContent = '已选 ' + n + ' 项';
    }

    function refresh(host) {
      host.innerHTML = table();
      bind(host);
      syncBulk();
      const t = $('#g-total');
      if (t) t.textContent = '共 ' + totalCount() + ' 条';
    }

    function bind(host) {
      const rows = cfg.tree ? treeVisible() : pageRows();
      host.querySelectorAll('[data-caret]').forEach(b => b.onclick = (e) => {
        e.stopPropagation();
        const id = Number(b.dataset.caret);
        if (st.collapsed.has(id)) st.collapsed.delete(id); else st.collapsed.add(id);
        refresh(host);
      });
      const allBox = host.querySelector('[data-all]');
      if (allBox) allBox.onchange = () => { rows.forEach(r => allBox.checked ? st.sel.add(r.id) : st.sel.delete(r.id)); refresh(host); };
      host.querySelectorAll('[data-check]').forEach(c => c.onchange = () => { c.checked ? st.sel.add(c.dataset.check) : st.sel.delete(c.dataset.check); refresh(host); });
      host.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => openForm(st.rows.find(x => x.id === b.dataset.edit)));
      host.querySelectorAll('[data-pdf]').forEach(b => b.onclick = async (e) => {
        e.stopPropagation();
        const r = st.rows.find(x => x.id === b.dataset.pdf) || {};
        const fid = r.fileId || (r.fileIds && r.fileIds[0]);
        if (!fid) { toast('warn', '该记录没有可预览的附件'); return; }
        await CarbonAPI.download('/files/' + fid + '/download');
      });
      host.querySelectorAll('[data-del]').forEach(b => b.onclick = async () => {
        const r = st.rows.find(x => x.id === b.dataset.del);
        const ok = await confirm({ title: '提示', content: '确定要删除选中项吗？', okText: '删除', danger: true });
        if (!ok) return;
        await CarbonAPI.deleteRow(cfg, r);
      });
      host.querySelectorAll('[data-dl]').forEach(b => b.onclick = async () => {
        const r = st.rows.find(x => x.id === b.dataset.dl);
        const fid = r && (r.fileId || (r.fileIds && r.fileIds[0]));
        if (fid) await CarbonAPI.download('/files/' + fid + '/download');
        else toast('warn', '该记录没有附件');
      });
      host.querySelectorAll('[data-disable]').forEach(b => b.onclick = async () => {
        const r = st.rows.find(x => x.id === b.dataset.disable);
        r.disabled = !r.disabled; refresh(host); toast('ok', '操作成功');
      });
      host.querySelectorAll('[data-view]').forEach(b => b.onclick = () => {
        const r = st.rows.find(x => x.id === b.dataset.view);
        const fs2 = (cfg.fields || []).filter(f => f.type !== 'file');
        modal({
          title: '查看 · ' + (r[cfg.nameKey || 'name'] || ''), width: 620,
          body: `<div class="grid grid-2" style="gap:2px 20px">${fs2.map(f => `<div class="between small" style="padding:7px 0;border-bottom:1px dashed var(--line)">
            <span class="muted">${esc(f.label)}</span><b style="text-align:right">${esc(r[f.k] ?? '—')}</b></div>`).join('')}</div>`,
          foot: `<button class="btn" data-close>关闭</button>`
        });
      });
      host.querySelectorAll('[data-child]').forEach(b => b.onclick = () => {
        const r = st.rows.find(x => x.id === b.dataset.child);
        openForm(null, { parentId: r.dbId, __title: cfg.childTitle || cfg.formTitle || '添加下级' });
      });
      host.querySelectorAll('[data-toggle]').forEach(b => b.onclick = async () => {
        const r = st.rows.find(x => x.id === b.dataset.toggle);
        if (r.enabled) { const ok = await confirm({ title: '停用事件', content: `停用后「${r.name}」不再产生预警，确认？`, okText: '停用' }); if (!ok) return; }
        const next = r.enabled ? 0 : 1;
        try {
          await CarbonAPI.request('POST', '/alert-events/' + r.dbId + '/toggle?enabled=' + next);
          r.enabled = next; refresh(host); toast(next ? 'ok' : 'warn', next ? '已启用' : '已停用', r.name);
        } catch (e) { toast('err', e.message); }
      });
      bindPager(host, async n => { st.page = n; st.sel.clear(); await loadPage(); refresh(host); });
    }

    function openForm(rec, preset) {
      const editing = !!rec;
      const fields = cfg.fields || [];
      const inner = fields.map(f => {
        const val = editing ? (rec[f.k] ?? '') : ((preset && preset[f.k] !== undefined) ? preset[f.k] : (f.def ?? ''));
        const ro = (editing && f.ro) || f.alwaysRo;
        const ctl = f.type === 'select'
          ? (() => {
              const raw = typeof f.options === 'function' ? f.options(rec) : (f.options || []);
              const opts = raw.map(o => (o && typeof o === 'object')
                ? { label: String(o.label == null ? o.value : o.label), value: String(o.value) }
                : { label: String(o), value: String(o) });
              return `<select class="select" name="${f.k}" ${ro ? 'disabled' : ''}>${f.empty ? `<option value="">${esc(f.emptyLabel || '')}</option>` : ''}${opts.map(o => `<option value="${esc(o.value)}" ${o.value === String(val) ? 'selected' : ''}>${esc(o.label)}</option>`).join('')}</select>`;
            })()
          : f.type === 'dateRange' ? (() => { const parts = String(val || '').split('~').map(x => x.trim()); return UI.rangePicker({ mode: 'date', from: parts[0] || '', to: parts[1] || '', ph: f.ph || '开始日期 ~ 结束日期' }); })()
          : f.type === 'files' ? `<div class="files-box"><div class="small muted" style="margin-bottom:6px">${esc(f.hint || '上传文件大小 5MB 以内')}</div>
              <div class="file-list">${((editing && rec[f.k]) || []).map((n, i) => `<div class="file-item"><span class="tag tag-info">PDF</span><b>${esc(n)}</b><span class="right"></span><button type="button" class="btn btn-sm btn-ghost" data-fdel="${i}">删除</button><button type="button" class="btn btn-sm" data-fdl="${i}">下载</button></div>`).join('') || '<div class="small muted" style="padding:6px 0">暂无文件</div>'}</div>
              <label class="upload-add">${UI.icon('upload', 15)} 上传文件<input type="file" hidden multiple data-fadd></label></div>`
          : f.type === 'month' ? `<div class="pick" data-mpick><input class="input" name="${f.k}" value="${esc(val)}" placeholder="选择月份" readonly ${ro ? 'disabled' : ''}><span class="pick-ico">${CAL_ICO}</span><div class="pick-pop"><div class="pick-head"><button type="button" data-my="-1" aria-label="上一年">‹</button><b></b><button type="button" data-my="1" aria-label="下一年">›</button></div><div class="pick-grid months"></div></div></div>`
          : f.type === 'file' ? `<label class="upload" data-upload><input type="file" name="${f.k}" accept="${f.accept || 'image/*'}" ${ro ? 'disabled' : ''} hidden>
              <span class="upload-empty"><span class="upload-ico">${icon('upload', 18)}</span><span class="upload-txt"><b>点击或拖拽上传</b><span>${f.hint || '支持 JPG / PNG，≤10MB，仅限一张'}</span></span></span>
              <span class="upload-done hide"><img alt="预览"><span class="upload-txt"><b class="upload-name"></b><span>点击可更换</span></span><button type="button" class="upload-del" aria-label="移除">×</button></span></label>`
          : f.type === 'textarea' ? `<textarea class="input" name="${f.k}" maxlength="${f.max || 500}" placeholder="${esc(f.ph || '')}" ${ro ? 'readonly' : ''}>${esc(val)}</textarea>`
          : `<input class="input" name="${f.k}" type="${f.type || 'text'}" maxlength="${f.max || 120}" value="${esc(val)}" placeholder="${esc(f.ph || '')}" ${f.step ? `step="${f.step}"` : ''} ${ro ? 'readonly' : ''}>`;
        const exist = editing && f.type === 'file' && rec[f.k]
          ? `<div class="file-chip"><span class="tag tag-info">PDF</span><span class="file-name">${esc(rec[f.k])}</span><button type="button" class="btn btn-sm" data-pdf-preview>预览</button></div>` : '';
        return `<div class="field" data-f="${f.k}" style="margin-bottom:10px${f.type === 'files' ? ';grid-column:1 / -1' : ''}"><label class="${f.req ? 'req' : ''}">${esc(f.label)}${f.unit ? `（${f.unit}）` : ''}</label>${exist}${ctl}<div class="err-msg"></div></div>`;
      }).join('');
      const body = `<form id="gf">${fields.length > 6 ? `<div class="grid grid-2" style="gap:0 14px">${inner}</div>` : inner}</form>`;
      modal({
        title: editing ? (cfg.editTitle || '编辑') : ((preset && preset.__title) || cfg.formTitle || '添加'), width: cfg.formWidth || 620, body,
        foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="gf-save">保存</button>`,
        onMount(wrap, close) {
          const form = wrap.querySelector('#gf');
          const setE = (n, m) => { const f = form.querySelector(`[data-f="${n}"]`); if (!f) return; f.classList.toggle('error', !!m); f.querySelector('.err-msg').textContent = m || ''; };
          form.querySelectorAll('input,select,textarea').forEach(i => i.oninput = () => setE(i.name, ''));
          // 字段联动：某字段变化后回填其它字段（如选产品自动带出单位）
          fields.forEach(f => {
            if (typeof f.onChange !== 'function') return;
            const el = form.querySelector(`[name="${f.k}"]`);
            if (!el) return;
            el.addEventListener('change', () => {
              f.onChange(el.value, form, setE);
              el.dispatchEvent(new Event('input', { bubbles: true }));
            });
          });
          wrap.querySelectorAll('[data-pdf-preview]').forEach(b => b.onclick = (e) => { e.preventDefault(); toast('warn', '保存后可在列表中查看附件'); });
          UI.bindRangePickers(wrap);
          wrap.querySelectorAll('[data-fadd]').forEach(inp => inp.onchange = () => {
            const list = inp.closest('.files-box').querySelector('.file-list');
            if (list.querySelector('.small.muted') && !list.querySelector('.file-item')) list.innerHTML = '';
            [...inp.files].forEach(f0 => {
              const row = document.createElement('div'); row.className = 'file-item';
              row.innerHTML = `<span class="tag tag-info">PDF</span><b>${esc(f0.name)}</b><span class="right"></span><button type="button" class="btn btn-sm btn-ghost">删除</button>`;
              row.querySelector('button').onclick = () => row.remove();
              list.appendChild(row);
            });
            toast('ok', '文件已添加', '保存后生效');
          });
          wrap.querySelectorAll('.file-item').forEach(row => {
            const del = row.querySelector('[data-fdel]'), dl = row.querySelector('[data-fdl]');
            if (del) del.onclick = () => row.remove();
            if (dl) dl.onclick = () => toast('ok', '开始下载', row.querySelector('b').innerText);
          });
          // 月份选择器（内联面板，避免被弹窗裁切）
          form.querySelectorAll('[data-mpick]').forEach(box => {
            const inp = box.querySelector('input'), pop = box.querySelector('.pick-pop'), head = box.querySelector('.pick-head b'), grid = box.querySelector('.pick-grid');
            if (inp.disabled) return;
            let y = Number((inp.value || localMonth()).slice(0, 4));
            const pad = n => String(n).padStart(2, '0');
            const paint = () => {
              head.textContent = y + ' 年';
              grid.innerHTML = Array.from({ length: 12 }, (_, i) => { const m = pad(i + 1); return `<button type="button" data-m="${m}" class="${inp.value === y + '-' + m ? 'on' : ''}">${i + 1}月</button>`; }).join('');
              grid.querySelectorAll('[data-m]').forEach(b => b.onclick = () => { inp.value = y + '-' + b.dataset.m; pop.classList.remove('open'); setE(inp.name, ''); });
            };
            inp.onclick = () => { paint(); pop.classList.toggle('open'); };
            box.querySelectorAll('[data-my]').forEach(b => b.onclick = () => { y += Number(b.dataset.my); paint(); });
          });
          // 上传卡片：点击/拖拽、缩略图、移除
          form.querySelectorAll('[data-upload]').forEach(box => {
            const input = box.querySelector('input[type=file]'), empty = box.querySelector('.upload-empty'), done = box.querySelector('.upload-done');
            const img = box.querySelector('img'), nameEl = box.querySelector('.upload-name');
            const sync = () => {
              const f0 = input.files && input.files[0];
              if (!f0) { empty.classList.remove('hide'); done.classList.add('hide'); return; }
              nameEl.textContent = f0.name;
              if (f0.type && f0.type.indexOf('image/') === 0) { img.src = URL.createObjectURL(f0); img.style.display = ''; } else img.style.display = 'none';
              empty.classList.add('hide'); done.classList.remove('hide');
            };
            input.onchange = sync;
            ['dragenter', 'dragover'].forEach(ev => box.addEventListener(ev, e => { e.preventDefault(); box.classList.add('drag'); }));
            ['dragleave', 'drop'].forEach(ev => box.addEventListener(ev, e => { e.preventDefault(); box.classList.remove('drag'); }));
            box.addEventListener('drop', e => { if (e.dataTransfer.files.length) { input.files = e.dataTransfer.files; sync(); } });
            const del = box.querySelector('.upload-del');
            if (del) del.onclick = (e) => { e.preventDefault(); e.stopPropagation(); input.value = ''; if (img.src) URL.revokeObjectURL(img.src); sync(); };
          });
          wrap.addEventListener('click', e => { if (!e.target.closest('[data-mpick]')) form.querySelectorAll('[data-mpick] .pick-pop.open').forEach(x => x.classList.remove('open')); });
          wrap.querySelector('#gf-save').onclick = async (e) => {
            const d = Object.fromEntries(new FormData(form));
            fields.filter(f => f.type === 'dateRange').forEach(f => {
              const box = form.querySelector(`[data-f="${f.k}"] [data-rp]`);
              if (box) d[f.k] = box.dataset.from ? box.dataset.from + ' ~ ' + (box.dataset.to || '') : '';
            });
            let bad = false;
            fields.forEach(f => {
              if (f.type === 'file') return;
              const v = d[f.k];
              const empty = v == null || String(v).trim() === '';
              if (f.req && empty) { setE(f.k, f.label + ' 必填'); bad = true; return; }
              if (f.type !== 'number' || empty) return;
              const n = Number(v);
              if (!Number.isFinite(n)) { setE(f.k, f.label + ' 必须为数字'); bad = true; return; }
              const min = f.min ?? 0;
              if (n < min) { setE(f.k, f.label + '不能小于 ' + min); bad = true; }
              else if (f.max != null && n > f.max) { setE(f.k, f.label + '不能大于 ' + f.max); bad = true; }
            });
            if (!bad && cfg.validate) { const msg = cfg.validate(d, st.rows, rec); if (msg) { setE(msg.k, msg.m); bad = true; } }
            if (bad) { toast('err', '参数验证错误，请仔细填写表单数据!'); return; }
            const handled = await CarbonAPI.saveListPage(cfg, d, rec, editing, form);
            if (!handled) toast('warn', '该页写操作尚未接入后端', cfg.title);
            else close();
          };
        }
      });
    }

    return {
      title: cfg.title, group: cfg.group,
      render() {
        const writable = !!(window.CarbonAPI && CarbonAPI.canWrite && CarbonAPI.canWrite());
        const admin = !!(window.CarbonAPI && CarbonAPI.isAdmin && CarbonAPI.isAdmin());
        return `<div class="page">
          <div class="page-head"><div><h1 class="page-title">${cfg.title}</h1><p class="page-desc">${cfg.desc || ''}</p></div>
            <div class="page-actions">
              ${!writable || cfg.import === false || !IMPORT_RES[cfg.title] ? '' : `<button class="btn" id="g-import">${icon('upload', 15)} 导入</button>`}
              ${cfg.export === false ? '' : `<button class="btn" id="g-export">${icon('download', 15)} 导出</button>`}
              ${cfg.childTop && writable ? `<button class="btn" id="g-child">添加下级</button>` : ''}
              ${writable ? `<button class="btn btn-primary" id="g-new">${icon('plus', 15)} 新建</button>` : ''}</div></div>
          <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
            ${(cfg.filters || [{ label: cfg.filterLabel || '名称', type: 'text', key: 'kw' }]).map(f => {
              const isRange = f.type === 'monthRange' || f.type === 'dateRange';
              return `<div class="field" ${isRange ? `data-rfilter="${f.key}" style="min-width:236px"` : ''}><label>${esc(f.label)}</label>${isRange
                ? UI.rangePicker({ mode: f.type === 'monthRange' ? 'month' : 'date', ph: f.ph || '开始 ~ 结束' })
                : f.type === 'select'
                  ? UI.selectMenu({ key: f.key, value: st.filters[f.key] || '', options: ['全部', ...(typeof f.options === 'function' ? f.options() : (f.options || []))] })
                  : `<input class="input" data-filter="${f.key}" placeholder="${esc(f.ph || ('输入' + f.label))}">`}</div>`;
            }).join('')}
            <div class="field"><label>&nbsp;</label><div class="toolbar"><button class="btn btn-primary" id="g-search">查询</button><button class="btn" id="g-reset">重置</button></div></div>
            ${cfg.bulk === false || !(window.CarbonAPI && CarbonAPI.isAdmin && CarbonAPI.isAdmin()) ? '' : `<div class="field f-actions"><label>&nbsp;</label><button class="btn btn-danger" id="g-bulk" disabled>批量删除</button></div>`}
          </div></div></section>
          <section class="card"><div class="card-head"><h2 class="card-title">${cfg.title}列表</h2>
            <span class="card-sub" id="g-total">${server && !st.loaded ? '加载中…' : '共 ' + totalCount() + ' 条'}</span>
            ${cfg.bulk === false ? '' : `<span class="small muted right" id="g-sel">已选 0 项</span>`}</div>
            <div id="g-table">${table()}</div></section></div>`;
      },
      mount() {
        const host = $('#g-table');
        UI.bindRangePickers(document.querySelector('.page'));
        UI.bindSelects(document.querySelector('.page'));
        const paint = async () => { await loadPage(); refresh(host); };
        if (server) paint(); else { bind(host); syncBulk(); }
        const readFilters = () => {
          st.filters = {}; st.kw = '';
          document.querySelectorAll('[data-filter]').forEach(el => {
            const k = el.dataset.filter, v = (el.value || '').trim();
            if (!v || v === '全部') return;
            if (k === 'kw') st.kw = v; else st.filters[k] = v;
          });
          document.querySelectorAll('[data-rfilter]').forEach(f => {
            const box = f.querySelector('[data-rp]'); if (!box) return;
            const from = box.dataset.from || '', to = box.dataset.to || '';
            if (from || to) st.filters[f.dataset.rfilter] = { from, to };
          });
        };
        $('#g-search').onclick = async () => { readFilters(); st.page = 1; st.sel.clear(); await paint(); toast('ok', '查询完成', totalCount() + ' 条'); };
        $('#g-reset').onclick = async () => {
          document.querySelectorAll('[data-filter]').forEach(el => { el.value = el.tagName === 'SELECT' ? '全部' : ''; });
          UI.resetSelects(document.querySelector('.page'));
          document.querySelectorAll('[data-rfilter] [data-rp]').forEach(box => { box.dataset.from = ''; box.dataset.to = ''; box.querySelector('input').value = ''; });
          st.filters = {}; st.kw = ''; cfg.filterValue = null; st.page = 1; st.sel.clear();
          await paint();
        };
        document.querySelectorAll('[data-filter]').forEach(el => el.onkeydown = e => { if (e.key === 'Enter') $('#g-search').click(); });
        const gnew = $('#g-new');
        if (gnew) gnew.onclick = () => openForm();
        const gc = $('#g-child');
        if (gc) gc.onclick = () => openForm();
        const exp = $('#g-export');
        if (exp) exp.onclick = async (e) => {
          await CarbonAPI.exportResource(cfg);
        };
        const imp = $('#g-import');
        if (imp) imp.onclick = () => {
          modal({ title: '导入' + cfg.title.replace(/管理$/, '') + '数据', width: 540,
            body: `<div class="stack">
              <button class="btn" id="imp-tpl" style="justify-content:center">${icon('download', 16)} 下载模板</button>
              <div class="small muted">CSV 文件（UTF-8）</div>
              <label class="btn" style="justify-content:center;height:auto;padding:10px 12px;cursor:pointer">
                ${icon('upload', 16)} <span id="imp-name">上传 CSV 文件</span>
                <input type="file" id="imp-file" accept=".csv" style="display:none">
              </label>
              <div class="small muted">列顺序：设备编码,时间,数值,单位,换算率,来源</div>
              <div class="hr" style="margin:8px 0"></div>
              <div class="small" style="font-weight:600">导入说明：</div>
              <ol class="small muted" style="padding-left:18px;margin:4px 0">
                <li>请先下载标准模板，按照模板格式填写数据</li>
                <li>只能上传一个 CSV 文件</li>
                <li>删除已上传的文件可重新上传</li></ol>
              <div class="row small hide" id="imp-del-wrap" style="gap:8px"><span class="muted" id="imp-file-name"></span>
                <button class="btn btn-sm btn-ghost" id="imp-del">删除文件</button></div>
            </div>`,
            foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="imp-run">开始导入</button>`,
            onMount(w, c) {
              const file = w.querySelector('#imp-file'), name = w.querySelector('#imp-name');
              const wrapDel = w.querySelector('#imp-del-wrap'), fileName = w.querySelector('#imp-file-name');
              w.querySelector('#imp-tpl').onclick = async (e) => {
                const done = busy(e.currentTarget, '生成中');
                try { await CarbonAPI.download('/import/template?resource=' + IMPORT_RES[cfg.title]); done(); }
                catch (err) { done(); toast('err', err.message); }
              };
              file.onchange = () => {
                const f0 = file.files[0];
                name.textContent = f0 ? '已选择文件' : '上传 CSV 文件';
                wrapDel.classList.toggle('hide', !f0);
                fileName.textContent = f0 ? f0.name : '';
              };
              w.querySelector('#imp-del').onclick = () => { file.value = ''; name.textContent = '上传 CSV 文件'; wrapDel.classList.add('hide'); };
              w.querySelector('#imp-run').onclick = async (e) => {
                if (!file.files[0]) { toast('err', '请先上传 CSV 文件'); return; }
                const done = busy(e.currentTarget, '导入中');
                try {
                  const fd = new FormData();
                  fd.append('file', file.files[0]);
                  const res = IMPORT_RES[cfg.title];
                  const r = await CarbonAPI.request('POST',
                    res ? '/import?resource=' + res : '/device-readings/import', fd);
                  done(); c();
                  const ws = r.warnings || [];
                  const allParent = ws.length > 0 && ws.every(w => String(w.reason || '').includes('上级供应商'));
                  const tip = ws.length
                    ? '，' + ws.length + (allParent ? ' 条上级未匹配已置一级' : ' 条告警（明细见控制台）')
                    : '';
                  toast(r.failed || ws.length ? 'warn' : 'ok', '导入完成',
                    '成功 ' + r.success + ' 条，失败 ' + r.failed + ' 条' + tip);
                  if (r.errors && r.errors.length) console.warn('导入失败明细', r.errors);
                  if (ws.length) console.warn('导入告警明细', r.warnings);
                  await CarbonAPI.reloadQuiet();
                } catch (err) { done(); toast('err', err.message); }
              };
            } });
        };
        const bulk = $('#g-bulk');
        if (bulk) bulk.onclick = async () => {
          const rows = st.rows.filter(r => st.sel.has(r.id));
          if (!rows.length) return;
          const ok = await confirm({ title: '批量删除', content: `将删除选中的 ${rows.length} 条记录，删除后不可恢复。`, okText: '删除', danger: true });
          if (!ok) return;
          const r = await CarbonAPI.deleteRows(cfg, rows);
          st.sel.clear();
          toast(r.fail ? 'warn' : 'ok', '批量删除完成', '成功 ' + r.ok + ' 条，失败 ' + r.fail + ' 条');
          await CarbonAPI.reloadQuiet();
        };
      }
    };
  }

  /* ================= 台账页面注册 ================= */
  const flatFacilities = () => {
    const out = [];
    const walk = (nodes, parent, level) => nodes.forEach(n => {
      if (n.children) {
        out.push({ id: n.id, dbId: n.dbId, name: n.name, code: n.id, parent: parent || '—', level, status: '正常', remark: n.remark || '' });
        walk(n.children, n.name, level + 1);
      }
    });
    walk(DB.facilities, '', 1);
    return out;
  };

  route('/carbon-product/product/list', listPage({
    title: '产品列表', group: '产值管理', desc: '产品主数据与单位产品能耗分级，供产值与足迹引用',
    rows: DB.products, nameKey: 'name', kwFields: ['name', 'code'], idPrefix: 'PD-',
    filters: [{ label: '产品名称', type: 'text', key: 'kw', ph: '输入产品名称' },
      { label: '产品类型', type: 'select', key: 'type', options: ['一般产品', '低碳产品', '零碳产品'] }],
    columns: [
      { k: 'name', t: '产品名称', type: 'main', sub: 'id' },
      { k: 'type', t: '产品类型' }, { k: 'model', t: '规格型号' },
      { k: 'cls', t: '产品分类' }, { k: 'unit', t: '单位' },
      { k: 'l1', t: '1级单位产品能耗值', type: 'num', d: 2 },
      { k: 'l2', t: '2级单位产品能耗值', type: 'num', d: 2 },
      { k: 'l3', t: '3级单位产品能耗值', type: 'num', d: 2 },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) },
      { k: 'disabled', t: '禁用状态', render: r => r.disabled ? '<span class="tag tag-muted dot">已禁用</span>' : '<span class="tag tag-ok dot">启用</span>' },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' }],
    fields: [
      { k: 'name', label: '产品名称', req: true },
      { k: 'model', label: '规格型号' },
      { k: 'type', label: '产品类型', type: 'select', req: true, def: '一般产品', options: ['一般产品', '低碳产品', '零碳产品'] },
      { k: 'cls', label: '产品分类', type: 'select', req: true, def: '工业产品', options: ['工业产品', '农业产品', '其他产品'] },
      { k: 'unit', label: '单位', type: 'select', req: true, empty: true, options: ['吨', '千吨', '万吨', '克', '千克', '个', '万个', '件', '万件', '箱'] },
      { k: 'l1', label: '1级单位产品能耗值', type: 'number', step: '0.01' },
      { k: 'l2', label: '2级单位产品能耗值', type: 'number', step: '0.01' },
      { k: 'l3', label: '3级单位产品能耗值', type: 'number', step: '0.01' },
      { k: 'remark', label: '备注', type: 'textarea' }],
    formWidth: 720,
  }));

  route('/carbon-output/production-value/list', listPage({
    title: '产品产值', group: '产值管理', desc: '按产品与月份登记产值、产量，服务强度指标',
    rows: DB.prodValue, nameKey: 'product', kwFields: ['product', 'period'], idPrefix: 'PV-',
    filters: [{ label: '日期', type: 'monthRange', key: 'period', field: 'period', ph: '开始月份 ~ 结束月份' }],
    columns: [
      { k: 'period', t: '日期', type: 'main', sub: 'id' }, { k: 'product', t: '产品名称' },
      { k: 'value', t: '产值', type: 'num', d: 0 }, { k: 'unit', t: '产值单位' },
      { k: 'type', t: '产值类型', render: () => '产品产值' },
      { k: 'qty', t: '产量', type: 'int' }, { k: 'qtyUnit', t: '产量单位' },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) }],
    fields: [
      { k: 'product', label: '产品', type: 'select', req: true, empty: true, options: () => (DB.products || []).map(p => p.name) },
      { k: 'value', label: '产值', type: 'number', req: true },
      { k: 'qty', label: '产量', type: 'number', req: true },
      { k: 'qtyUnit', label: '产量单位', type: 'select', empty: true, options: ['吨', '千吨', '万吨', '克', '千克', '个', '万个', '件', '万件', '箱'] },
      { k: 'period', label: '月份', type: 'month', req: true },
      { k: 'remark', label: '备注', type: 'textarea' }],
  }));

  route('/carbon-output/total-value/list', listPage({
    title: '生产总值', group: '产值管理', desc: '全厂口径的总产量与总产值，按月份登记',
    rows: DB.totalValue, nameKey: 'name', kwFields: ['name', 'period'], idPrefix: 'TV-',
    filters: [{ label: '日期', type: 'monthRange', key: 'period', field: 'period', ph: '开始月份 ~ 结束月份' }],
    columns: [
      { k: 'period', t: '日期', type: 'main', sub: 'id' }, { k: 'name', t: '生产总值名称' },
      { k: 'qty', t: '总产量', type: 'int' }, { k: 'qtyUnit', t: '总产量单位' },
      { k: 'value', t: '总产值', type: 'num', d: 0 }, { k: 'unit', t: '总产值单位' },
      { k: 'type', t: '产值类型', render: () => '生产总值' },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) }],
    fields: [
      { k: 'period', label: '月份', type: 'month', req: true },
      { k: 'name', label: '生产总值名称' },
      { k: 'qty', label: '总产量', type: 'number', req: true },
      { k: 'qtyUnit', label: '总产量单位', type: 'select', req: true, empty: true, options: ['吨', '千吨', '万吨', '克', '千克', '个', '万个', '件', '万件', '箱'] },
      { k: 'value', label: '总产值', type: 'number', req: true },
      { k: 'remark', label: '备注', type: 'textarea' }],
  }));

  route('/carbon-asset/asset/list', listPage({
    title: '碳配额资产管理', group: '碳资产管理', desc: '配额资产的持有、类型与年度归属',
    rows: DB.assets, nameKey: 'name', kwFields: ['name', 'type'], idPrefix: 'AS-', actions: ['edit', 'delete', 'disable'],
    filters: [{ label: '年度', type: 'select', key: 'year', options: (() => { const y = new Date().getFullYear(); return [String(y), String(y - 1)]; })() },
      { label: '资产类型', type: 'select', key: 'type', options: ['配额资产', '信用资产'] }],
    columns: [
      { k: 'name', t: '资产名称', type: 'main', sub: 'id' }, { k: 'qty', t: '资产数量', type: 'int' },
      { k: 'unit', t: '单位' }, { k: 'type', t: '资产类型' },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) },
      { k: 'year', t: '年度' },
      { k: 'disabled', t: '禁用状态', render: r => r.disabled ? '<span class="tag tag-muted dot">已禁用</span>' : '<span class="tag tag-ok dot">启用</span>' }],
    fields: [
      { k: 'name', label: '资产名称', req: true },
      { k: 'qty', label: '资产数量', type: 'number', req: true },
      { k: 'unit', label: '单位', type: 'select', empty: true, options: ['万元', 'tCO₂e', 'kgCO₂e', 'gCO₂e', 'CO₂e', '张', 'MW·h'] },
      { k: 'type', label: '资产类型', type: 'select', def: '信用资产', options: ['配额资产', '信用资产'] },
      { k: 'remark', label: '备注', type: 'textarea' },
      { k: 'year', label: '年度', type: 'number', ro: true }],
  }));

  route('/carbon-asset/ccer/list', listPage({
    title: '信用资产管理', group: '碳资产管理', desc: 'CCER 等信用资产的备案状态与年度归属',
    rows: DB.ccers, nameKey: 'name', kwFields: ['name', 'type'], idPrefix: 'CC-',
    filters: [{ label: '年度', type: 'select', key: 'year', options: (() => { const y = new Date().getFullYear(); return [String(y), String(y - 1)]; })() }],
    columns: [
      { k: 'name', t: '资产名称', type: 'main', sub: 'id' }, { k: 'qty', t: '资产数量', type: 'int' },
      { k: 'unit', t: '单位' }, { k: 'type', t: '资产类型' },
      { k: 'status', t: '资产状态', type: 'tag', map: { 已备案: 'tag-ok', 待核证: 'tag-warn', 待审定: 'tag-warn', 待申请: 'tag-muted' } },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) }, { k: 'year', t: '年度' }],
    fields: [
      { k: 'name', label: '资产名称', req: true },
      { k: 'qty', label: '资产数量', type: 'number', req: true },
      { k: 'unit', label: '单位', type: 'select', req: true, empty: true, options: ['万元', 'tCO₂e', 'kgCO₂e', 'gCO₂e', 'CO₂e', '张', 'MW·h'] },
      { k: 'type', label: '资产类型', type: 'select', req: true, def: '光热发电', options: ['光热发电', '海上风力发电', '公路隧道照明', '红树林营造', '煤矿瓦斯利用', '造林碳汇', '中深层地热能井下换热供暖', '可再生能源电解水制氢', '公共建筑', '海上油田伴生气回收利用'] },
      { k: 'status', label: '资产状态', type: 'select', req: true, def: '待申请', options: ['待申请', '待审定', '已签发', '已交易'] },
      { k: 'remark', label: '备注', type: 'textarea' },
      { k: 'year', label: '年度', type: 'number', ro: true }],
  }));

  route('/carbon-asset/trade/list', listPage({
    title: '交易管理', group: '碳资产管理', desc: '配额与信用的买入卖出流水，金额自动核算',
    rows: DB.trades, nameKey: 'asset', kwFields: ['asset', 'party'], idPrefix: 'TR-',
    filters: [{ label: '成交日期', type: 'dateRange', key: 'date', field: 'date', ph: '开始日期 ~ 结束日期' },
      { label: '资产类型', type: 'select', key: 'assetType', options: ['配额资产', '信用资产'] },
      { label: '交易方向', type: 'select', key: 'type', options: ['购入', '售出'] }],
    columns: [
      { k: 'asset', t: '资产类型', type: 'main', sub: 'id' }, { k: 'qty', t: '交易数量(tCO2e)', type: 'int' },
      { k: 'amount', t: '交易金额(元)', type: 'int' },
      { k: 'type', t: '交易类型', type: 'tag', map: { 购入: 'tag-info', 售出: 'tag-warn', 买入: 'tag-info', 卖出: 'tag-warn' } },
      { k: 'party', t: '购买／出售方' }, { k: 'use', t: '用途' }, { k: 'date', t: '交易日期' }],
    fields: [
      { k: 'asset', label: '资产名称', type: 'select', req: true, empty: true, options: () => (DB.assets || []).map(a => a.name) },
      { k: 'qty', label: '交易数量(tCO2e)', type: 'number', req: true },
      { k: 'price', label: '交易单价(元)', type: 'number', req: true },
      { k: 'amount', label: '交易金额(元)', type: 'number', ph: '数量×单价', ro: true },
      { k: 'assetType', label: '资产类型', type: 'select', req: true, def: '信用资产', options: ['配额资产', '信用资产'] },
      { k: 'type', label: '交易类型', type: 'select', def: '购入', options: ['购入', '售出'] },
      { k: 'party', label: '购买／出售方' },
      { k: 'use', label: '用途', ph: '选填' },
      { k: 'date', label: '成交日期', type: 'date', req: true, def: localDate() }],
    validate(d) {
      if (d.qty && d.price && Number(d.qty) * Number(d.price) <= 0) return { k: 'qty', m: '交易数量×单价需大于 0' };
      return null;
    },
  }));

  route('/carbon-verify/evidence/list', listPage({
    title: '原始凭证管理', group: '碳核查支撑', desc: '核算留痕的原始凭证归档，支持下载核对',
    rows: DB.evidences, nameKey: 'name', kwFields: ['name', 'code'], idPrefix: 'EV-',
    import: false, export: false, actions: ['edit', 'delete', 'download'],
    filters: [{ label: '凭证名称', type: 'text', key: 'kw', ph: '输入凭证名称' }],
    columns: [
      { k: 'name', t: '凭证名称', type: 'main' }, { k: 'code', t: '凭证编码' }, { k: 'desc', t: '凭证描述' },
      { k: 'valid', t: '有效期限' },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) }],
    fields: [
      { k: 'name', label: '凭证名称', req: true }, { k: 'code', label: '凭证编码' },
      { k: 'desc', label: '凭证描述' },
      { k: 'valid', label: '有效期限', type: 'dateRange' },
      { k: 'remark', label: '备注' },
      { k: 'files', label: '上传文件', type: 'files', hint: '上传文件大小 5MB 以内' }],
  }));

  route('/carbon-verify/report/list', listPage({
    title: '第三方报告管理', group: '碳核查支撑', desc: '第三方核查报告归档，记录有效期与文件',
    rows: DB.thirdReports, nameKey: 'name', kwFields: ['name', 'desc'], idPrefix: 'TP-',
    import: false, export: false, actions: ['edit', 'delete', 'download'],
    filters: [{ label: '报告名称', type: 'text', key: 'kw', ph: '输入报告名称' }],
    columns: [
      { k: 'name', t: '报告名称', type: 'main', sub: 'id' }, { k: 'desc', t: '报告描述' },
      { k: 'file', t: '报告文件', render: r => `<button class="btn btn-sm btn-ghost pdf-link" data-pdf="${r.id}"><span class="tag tag-info">PDF</span> 预览</button>` },
      { k: 'valid', t: '有效期限' },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) }],
    fields: [
      { k: 'name', label: '报告名称', req: true }, { k: 'desc', label: '报告描述', type: 'textarea' },
      { k: 'file', label: '报告文件', type: 'file', req: true, hint: '支持 PDF，≤10MB，仅限一个' },
      { k: 'valid', label: '有效期限', ph: '开始日期 ~ 结束日期', ro: true },
      { k: 'remark', label: '备注', type: 'textarea' }],
  }));

  route('/carbon-facility/facility/list', listPage({
    title: '设施管理', group: '设备设施', desc: '厂区—车间两级设施结构，可添加下级',
    rows: flatFacilities, nameKey: 'name', kwFields: ['name', 'code'], idPrefix: 'FA-', sortKey: 'id',
    bulk: false, actions: ['edit', 'delete', 'disable', 'addChild'], childText: '添加下级', formTitle: '添加设施', editTitle: '编辑设施',
    filters: [{ label: '设施名称', type: 'text', key: 'kw', ph: '输入设施名称' }],
    columns: [
      { k: 'name', t: '设施名称', type: 'main', sub: 'code' }, { k: 'code', t: '设施编码' },
      { k: 'parent', t: '上级设施', render: r => r.parent && r.parent !== '—' ? esc(r.parent) : '<span class="muted">—</span>' },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) },
      { k: 'disabled', t: '状态', render: r => r.disabled ? '<span class="tag tag-muted dot">已禁用</span>' : '<span class="tag tag-ok dot">正常</span>' }],
    fields: [
      { k: 'parent', label: '上级设施', type: 'select', options: () => ['—', ...flatFacilities().map(f => f.name)] },
      { k: 'name', label: '设施名称', req: true },
      { k: 'code', label: '设施编码', req: true },
      { k: 'sort', label: '排序', type: 'number', def: 1 },
      { k: 'remark', label: '备注' }],
  }));

  route('/carbon-facility/device-data/list', listPage({
    title: '设备数据', group: '设备设施', desc: '采集与补录的设备读数，单位换算率需人工确认',
    rows: DB.deviceData, nameKey: 'device', kwFields: ['device', 'id'], idPrefix: 'DD-',
    filters: [{ label: '设备', type: 'text', key: 'kw', ph: '输入设备名称' },
      { label: '日期', type: 'dateRange', key: 'date', field: 'date', ph: '开始日期 ~ 结束日期' },
      { label: '状态', type: 'select', key: 'status', options: ['正常', '异常', '未知'] }],
    columns: [
      { k: 'device', t: '设备', type: 'main', sub: 'id' }, { k: 'date', t: '日期' },
      { k: 'qty', t: '数值', type: 'num', d: 1 }, { k: 'unit', t: '单位' },
      { k: 'rate', t: '换算率', type: 'num', d: 1 },
      { k: 'source', t: '来源', type: 'tag', map: { 自动采集: 'tag-info', 手工补录: 'tag-warn' } },
      { k: 'status', t: '状态', type: 'tag', map: { 正常: 'tag-ok', 异常: 'tag-danger' } },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' }],
    fields: [
      { k: 'device', label: '设备', type: 'select', req: true, options: () => (DB.devices || []).map(d => d.name) },
      { k: 'date', label: '日期', req: true, ph: 'YYYY-MM-DD HH:mm', def: localDate() + ' 08:00' },
      { k: 'qty', label: '数值', type: 'number', req: true },
      { k: 'unit', label: '单位', type: 'select', req: true, options: ['mm', 'cm', 'm', 'g', 'kg', 't', 'Pa', 'KPa', 'MPa', 'W·h'] },
      { k: 'rate', label: '换算率', type: 'number', req: true, def: 1 },
      { k: 'source', label: '来源', type: 'select', options: ['自动采集', '手工补录'] },
      { k: 'status', label: '状态', type: 'select', options: ['正常', '异常', '未知'] },
      { k: 'remark', label: '备注', type: 'textarea' }],
  }));

  route('/common-warning/event/list', listPage({
    title: '事件预警', group: '预警管理', desc: '可预警的事件字典，内置事件不可删除',
    rows: DB.warningEvents, nameKey: 'name', kwFields: ['name', 'code'], idPrefix: 'WE-',
    import: false, export: false, actions: ['edit', 'delete', 'toggle'],
    filters: [{ label: '事件类型', type: 'select', key: 'type', options: ['系统', '碳排放', '能耗'] },
      { label: '事件名称', type: 'text', key: 'kw', ph: '输入事件名称' }],
    columns: [
      { k: 'name', t: '事件名称', type: 'main' },
      { k: 'type', t: '事件类型', type: 'tag', map: { 系统: 'tag-info', 碳排放: 'tag-warn', 能耗: 'tag-muted' } },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' },
      { k: 'builtin', t: '内置', render: r => r.builtin ? '<span class="tag tag-info">内置</span>' : '<span class="tag tag-muted">自定义</span>' },
      { k: 'enabled', t: '状态', type: 'bool' }],
    fields: [
      { k: 'name', label: '事件名称', req: true },
      { k: 'type', label: '事件类型', type: 'select', req: true, options: ['系统', '碳排放', '能耗'] },
      { k: 'remark', label: '备注', type: 'textarea' }],
  }));

  route('/carbon-economize/renovation-project/list', listPage({
    title: '节能改造项目', group: '节能管理', desc: '改造项目投资、节能量与合同模式台账',
    rows: DB.retrofits, nameKey: 'name', kwFields: ['name'], idPrefix: 'RP-',
    filters: [{ label: '年度', type: 'select', key: 'year', options: (() => { const y = new Date().getFullYear(); return [String(y), String(y - 1)]; })() },
      { label: '项目名称', type: 'text', key: 'kw', ph: '输入项目名称' }],
    columns: [
      { k: 'year', t: '年度' }, { k: 'name', t: '项目名称', type: 'main', sub: 'id' },
      { k: 'invest', t: '投资金额', type: 'num', d: 0 }, { k: 'investUnit', t: '投资金额单位', render: r => r.investUnit || '万元' },
      { k: 'saving', t: '节能量', type: 'num', d: 0 }, { k: 'savingUnit', t: '节能量单位', render: r => r.savingUnit || 'tce/年' },
      { k: 'contract', t: '是否合同模式', type: 'tag', map: { 是: 'tag-info', 否: 'tag-muted' } },
      { k: 'done', t: '完成状态', type: 'tag', map: { 是: 'tag-ok', 否: 'tag-warn', 已完成: 'tag-ok', 进行中: 'tag-warn' } },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' }],
    fields: [
      { k: 'year', label: '年度', type: 'number', req: true, ro: true },
      { k: 'name', label: '项目名称', req: true },
      { k: 'invest', label: '投资金额', type: 'number' },
      { k: 'investUnit', label: '投资金额单位', type: 'select', options: ['万元', '十万元', '百万元', '千万元', '亿元', '千元', '百元', '元'] },
      { k: 'saving', label: '节能量', type: 'number' },
      { k: 'savingUnit', label: '节能量单位', type: 'select', def: 'tce/年', options: ['mm', 'cm', 'm', 'g', 'kg', 't', 'Pa', 'KPa', 'MPa', 'W·h'] },
      { k: 'contract', label: '是否合同能源管理模式', type: 'select', def: '否', options: ['是', '否'] },
      { k: 'done', label: '项目是否已完成', type: 'select', def: '否', options: ['是', '否'] },
      { k: 'remark', label: '备注', type: 'textarea' }],
    formWidth: 760,
  }));

  route('/carbon-economize/management-situation/list', listPage({
    title: '能源管理情况', group: '节能管理', desc: '能源管理机构、岗位与填报人信息，按年填报',
    rows: DB.energyMgmt, nameKey: 'org', kwFields: ['org', 'duty'], idPrefix: 'EM-',
    import: false,
    filters: [{ label: '关键词', type: 'text', key: 'kw', ph: '机构名称 / 负责人' },
      { label: '能源管理机构名称', type: 'text', key: 'org', ph: '输入机构名称' }],
    columns: [
      { k: 'at', t: '填报日期', type: 'main', sub: 'id' }, { k: 'org', t: '能源管理机构名称' },
      { k: 'team', t: '机构管理人员名单及分工' }, { k: 'post', t: '能源管理岗位' },
      { k: 'duty', t: '能源管理负责人' }, { k: 'charge', t: '填报负责人' },
      { k: 'filler', t: '填报人' }, { k: 'contact', t: '电话' },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' }],
    fields: [
      { k: 'at', label: '填报日期', req: true, ph: 'YYYY-MM-DD' },
      { k: 'org', label: '能源管理机构名称或所在部门' },
      { k: 'team', label: '机构管理人员名单及分工' },
      { k: 'post', label: '能源管理岗位' },
      { k: 'duty', label: '能源管理负责人' },
      { k: 'record', label: '报管理节能工作部门备案情况' },
      { k: 'system', label: '能源管理体系建立情况' },
      { k: 'instName', label: '节能管理制度名称' },
      { k: 'stat', label: '能源统计、能源计量管理情况' },
      { k: 'promo', label: '节能宣传情况' },
      { k: 'training', label: '节能教育及培训情况' },
      { k: 'subsidy', label: '是否有无偿提供能源使用或发放能源补贴', type: 'select', options: ['是', '否'] },
      { k: 'report', label: '是否按要求报送上年度《能源利用状况报告》', type: 'select', options: ['是', '否'] },
      { k: 'charge', label: '填报负责人' },
      { k: 'filler', label: '填报人' },
      { k: 'contact', label: '电话' },
      { k: 'remark', label: '备注' }],
    formWidth: 760,
  }));

  route('/carbon-footprint/supplier-footprint/list', listPage({
    title: '供应链碳管理', group: '碳足迹管理', desc: '供应商维度建档，记录产品碳足迹与核算范围',
    rows: DB.suppliers, nameKey: 'name', kwFields: ['name', 'product'], idPrefix: 'SP-',
    tree: true, bulk: false, actions: ['edit', 'delete', 'addChild'], childText: '添加下级',
    filters: [{ label: '供应商名称', type: 'text', key: 'kw', ph: '输入供应商名称' },
      { label: '产品名称', type: 'text', key: 'product', ph: '输入产品名称' }],
    columns: [
      { k: 'name', t: '供应商名称', type: 'main', sub: 'id' }, { k: 'product', t: '产品名称' },
      { k: 'unit', t: '产品单位' },
      { k: 'footprint', t: '碳足迹值', type: 'num', d: 2 },
      { k: 'fpUnit', t: '碳足迹单位', render: r => r.fpUnit || 'tCO₂e/t' },
      { k: 'date', t: '碳足迹日期' },
      { k: 'target', t: '核算范围' },
      { k: 'orgName', t: '企业', render: () => esc(DB.org.short) },
      { k: 'level', t: '层级', render: r => `<span class="tag ${r.level === '一级' ? 'tag-info' : 'tag-muted'}">${esc(r.level || '一级')}</span>` },
      { k: 'remark', t: '备注', render: r => r.remark ? esc(r.remark) : '<span class="muted">—</span>' }],
    fields: [
      { k: 'parentId', label: '上级供应商', type: 'select', empty: true, emptyLabel: '无（一级供应商）',
        options: (rec) => {
          const all = DB.suppliers || [];
          if (!rec) return all.map(s => ({ label: s.name, value: s.dbId }));
          const banned = new Set([rec.dbId]);
          let changed = true;
          while (changed) {
            changed = false;
            all.forEach(s => { if (s.parentId != null && banned.has(s.parentId) && !banned.has(s.dbId)) { banned.add(s.dbId); changed = true; } });
          }
          return all.filter(s => !banned.has(s.dbId)).map(s => ({ label: s.name, value: s.dbId }));
        } },
      { k: 'name', label: '供应商名称', req: true },
      { k: 'product', label: '产品名称', type: 'select', req: true, empty: true, emptyLabel: '请选择产品',
        options: (rec) => {
          const list = (DB.products || []).map(p => ({ label: p.name, value: p.name }));
          // 历史记录的 product 可能不在产品列表里，保留原值，避免编辑时被下拉默认项覆盖
          const cur = rec && rec.product;
          if (cur && !list.some(o => o.value === cur)) {
            list.unshift({ label: cur + '（原值，已不在产品列表）', value: cur });
          }
          return list;
        },
        // 选中产品后把该产品在产品列表里登记的单位带过来
        onChange: (name, form, setE) => {
          const p = (DB.products || []).find(x => x.name === name);
          const u = form.querySelector('input[name="unit"]');
          if (u) u.value = p ? (p.unit || '') : '';
          setE('unit', '');
        } },
      { k: 'unit', label: '产品单位', alwaysRo: true, ph: '选择产品后自动带入' },
      { k: 'footprint', label: '碳足迹值', type: 'number', req: true, step: '0.01' },
      { k: 'fpUnit', label: '碳足迹单位', type: 'select', req: true, empty: true, options: ['tCO2e/t', 'kgCO2e/kg', 'gCO2e/g', 'tCO2e/万Nm³', 'tCO2e/Nm³', 'tCO2e/万个', 'kgCO2e/个', 'tCO2e/万件', 'kgCO2e/件', 'tCO2e/万m'] },
      { k: 'date', label: '碳足迹日期', type: 'date', req: true, def: localDate() },
      { k: 'target', label: '核算范围', type: 'select', def: '范围三', options: ['范围一', '范围二', '范围三'] },
      { k: 'sort', label: '排序', type: 'number', def: 1 },
      { k: 'remark', label: '备注', type: 'textarea' }],
  }));

  /* ================= 分析工厂 ================= */
  const PALETTE = ['#0f7a5a', '#f59e0b', '#64748b', '#94a3b8', '#cbd5e1'];

  const fmtV = (v, d = 2) => typeof v === 'number'
    ? v.toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d })
    : (v ?? '');

  function chartShell(title, sub, body, full) {
    return `<section class="card"${full ? ' style="grid-column:1/-1"' : ''}>
      <div class="card-head"><h2 class="card-title">${esc(title || '')}</h2>${sub ? `<span class="card-sub">${esc(sub)}</span>` : ''}</div>
      <div class="card-body"${full ? ' style="max-width:860px;margin:0 auto"' : ''}>${body}</div></section>`;
  }

  function legend(items) {
    return `<div class="row small" style="gap:16px;margin-bottom:8px;flex-wrap:wrap">${items.map(s =>
      `<span><i style="display:inline-block;width:12px;height:3px;border-radius:2px;background:${s.color};margin-right:6px;vertical-align:middle"></i>${esc(s.name)}</span>`).join('')}</div>`;
  }

  /* 横向条形图：右侧带数值标签 */
  function hBar(rows, unit = '') {
    const w = 680, rowH = 46, pad = { l: 112, r: 112, t: 6, b: 22 };
    const h = pad.t + pad.b + rows.length * rowH;
    const max = Math.max(...rows.map(r => Math.abs(r.value))) || 1;
    const bars = rows.map((r, i) => {
      const y = pad.t + i * rowH + 10;
      const bw = Math.max(2, (Math.abs(r.value) / max) * (w - pad.l - pad.r));
      return `<g><text x="${pad.l - 10}" y="${y + 14}" text-anchor="end" font-size="11" fill="#64748b">${esc(r.name)}</text>
        <rect x="${pad.l}" y="${y}" width="${w - pad.l - pad.r}" height="18" rx="4" fill="#f1f5f9"/>
        <rect x="${pad.l}" y="${y}" width="${bw}" height="18" rx="4" fill="${r.color || PALETTE[0]}"/>
        <text x="${pad.l + bw + 8}" y="${y + 13}" font-size="11" font-weight="600" fill="#0f172a">${fmtV(r.value, r.d ?? 2)} ${esc(r.unit || unit)}</text></g>`;
    }).join('');
    return `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="横向条形图">${bars}</svg>`;
  }

  /* 折线图：单/双轴、多序列 */
  function lineChart(labels, series, opts = {}) {
    const w = 700, h = opts.height || 210, pad = { l: 48, r: 48, t: 12, b: 26 };
    const left = series.filter(s => s.axis !== 'right');
    const right = series.filter(s => s.axis === 'right');
    const maxOf = arr => Math.max(...arr.flatMap(s => s.values.map(v => Math.abs(v))), 1) * 1.15;
    const maxL = maxOf(left.length ? left : series), maxR = right.length ? maxOf(right) : 1;
    const x = i => pad.l + (i * (w - pad.l - pad.r)) / Math.max(1, labels.length - 1);
    const yL = v => pad.t + (h - pad.t - pad.b) * (1 - v / maxL);
    const yR = v => pad.t + (h - pad.t - pad.b) * (1 - v / maxR);
    const grid = [0, 1, 2, 3].map(k => {
      const yy = pad.t + ((h - pad.t - pad.b) * k) / 3;
      const tv = maxL * (1 - k / 3);
      const lbl = tv >= 10000 ? (tv / 1000).toFixed(0) + 'k' : tv >= 100 ? tv.toFixed(0) : fmtV(tv, opts.axisD ?? 1);
      return `<line x1="${pad.l}" y1="${yy}" x2="${w - pad.r}" y2="${yy}" stroke="#eef2f6"/>
        <text x="${pad.l - 8}" y="${yy + 4}" text-anchor="end" font-size="10" fill="#94a3b8">${lbl}</text>`;
    }).join('');
    const paths = series.map(s => {
      const y = s.axis === 'right' ? yR : yL;
      const d = s.values.map((v, i) => `${i ? 'L' : 'M'}${x(i)},${y(v)}`).join(' ');
      const dots = s.values.map((v, i) => `<circle cx="${x(i)}" cy="${y(v)}" r="2.6" fill="${s.color}"/>`).join('');
      return `<path d="${d}" fill="none" stroke="${s.color}" stroke-width="2"/>${dots}`;
    }).join('');
    const xLabels = labels.map((l, i) => `<text x="${x(i)}" y="${h - 8}" text-anchor="middle" font-size="10" fill="#94a3b8">${esc(l)}</text>`).join('');
    return `${legend(series)}<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="折线图">${grid}${paths}${xLabels}</svg>`;
  }

  /* 雷达图 */
  function radar(axes, series) {
    const w = 380, h = 220, cx = w / 2, cy = h / 2 + 6, R = 72;
    const n = axes.length;
    const pt = (i, k) => {
      const a = (-Math.PI / 2) + (i * 2 * Math.PI) / n;
      return [cx + Math.cos(a) * R * k, cy + Math.sin(a) * R * k];
    };
    const rings = [0.25, 0.5, 0.75, 1].map(k =>
      `<polygon points="${axes.map((_, i) => pt(i, k).join(',')).join(' ')}" fill="none" stroke="#eef2f6"/>`).join('');
    const spokes = axes.map((_, i) => `<line x1="${cx}" y1="${cy}" x2="${pt(i, 1)[0]}" y2="${pt(i, 1)[1]}" stroke="#eef2f6"/>`).join('');
    const dots = axes.map((a, i) => {
      const p = pt(i, 1.16);
      return `<text x="${p[0]}" y="${p[1] + 3}" text-anchor="middle" font-size="10" fill="#64748b">${esc(a)}</text>`;
    }).join('');
    const polys = series.map(s => {
      const max = Math.max(...s.values, 1);
      const pts = s.values.map((v, i) => pt(i, Math.max(0.06, v / max)).join(',')).join(' ');
      return `<polygon points="${pts}" fill="${s.color}22" stroke="${s.color}" stroke-width="1.8"/>`;
    }).join('');
    return `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="雷达图">${rings}${spokes}${polys}${dots}</svg>`;
  }

  /* 桑基图：三层流向（总能耗 → 类别 → 品种） */
  function sankey(layers, links) {
    const w = 1040, h = 470, gap = 2, nodeW = 9;
    const xOf = i => [64, 430, 860][i] || 64;
    const pos = {};
    layers.forEach((layer, li) => {
      const total = layer.reduce((s, n) => s + Math.abs(n.value), 0) || 1;
      const usable = h - 56 - gap * (layer.length - 1);
      let y = 30;
      const even = li === 0 ? usable : Math.min(48, Math.max(22, (usable - gap * (layer.length - 1)) / layer.length));
      layer.forEach(n => {
        const hh = li === 0 ? usable : even;
        pos[n.id] = { x: xOf(li), y, h: hh, li, ...n };
        y += hh + gap;
      });
    });
    const total = layers[0].reduce((s, n) => s + Math.abs(n.value), 0) || 1;
    const outSum = {}, inSum = {};
    links.forEach(l => {
      const lv = l.value != null ? l.value : ((pos[l.to] || {}).value || 0);
      outSum[l.from] = (outSum[l.from] || 0) + lv;
      inSum[l.to] = (inSum[l.to] || 0) + lv;
    });
    const metas = links.map(l => {
      const a = pos[l.from], b = pos[l.to];
      if (!a || !b) return null;
      const lv = l.value != null ? l.value : (b.value || 0);
      const aH = a.h * (lv / (outSum[l.from] || lv));
      const bH = b.h * (lv / (inSum[l.to] || lv));
      return { l, a, b, lv, thick: Math.max(2, Math.min(aH, bH) - 1) };
    }).filter(Boolean);
    const fromTotal = {}, toTotal = {};
    metas.forEach(m => { fromTotal[m.l.from] = (fromTotal[m.l.from] || 0) + m.thick + 1; toTotal[m.l.to] = (toTotal[m.l.to] || 0) + m.thick + 1; });
    const outCur = {}, inCur = {};
    const ribbons = metas.map((m, i) => {
      const { l, a, b, lv, thick } = m;
      const aStart = a.y + Math.max(0, (a.h - fromTotal[l.from]) / 2);
      const bStart = b.y + Math.max(0, (b.h - toTotal[l.to]) / 2);
      const ay = aStart + (outCur[l.from] || 0) + thick / 2;
      const by = bStart + (inCur[l.to] || 0) + thick / 2;
      outCur[l.from] = (outCur[l.from] || 0) + thick + 1;
      inCur[l.to] = (inCur[l.to] || 0) + thick + 1;
      const x1 = a.x + nodeW, x2 = b.x, cx = (x1 + x2) / 2;
      return `<path class="sk-link" data-i="${i}" data-from="${esc(a.name)}" data-to="${esc(b.name)}" data-v="${lv}"
        d="M${x1},${ay} C${cx},${ay} ${cx},${by} ${x2},${by}" fill="none" stroke="${b.color || '#0f7a5a'}"
        stroke-opacity=".26" stroke-width="${thick}" stroke-linecap="round"><title>${esc(a.name)} → ${esc(b.name)}：${fmtV(lv, 2)} tce</title></path>`;
    }).join('');
    const nodes = Object.values(pos).map(n => {
      let tx = n.x + nodeW + 8, ty = n.y + n.h / 2 + 4, anchor = 'start';
      if (n.li === 0) { tx = n.x - 8; anchor = 'end'; }
      else if (n.li === 1) { tx = n.x; ty = n.y - 6; }
      const txt = `${n.name}${n.value !== undefined ? '：' + fmtV(n.value, 2) + ' tce' : ''}`;
      const chip = n.li === 1 ? `<rect x="${tx - 3}" y="${ty - 13}" width="${Math.min(240, txt.length * 7.4 + 10)}" height="17" rx="4" fill="#fff" fill-opacity=".82"/>` : '';
      return `<g class="sk-node" data-name="${esc(n.name)}" data-v="${n.value ?? ''}">
        <rect x="${n.x}" y="${n.y}" width="${nodeW}" height="${n.h}" rx="3" fill="${n.color || '#0f7a5a'}"/>
        ${chip}<text x="${tx}" y="${ty}" text-anchor="${anchor}" font-size="10.5" fill="#334155" stroke="#fff" stroke-width="3" paint-order="stroke">${esc(txt)}</text></g>`;
    }).join('');
    return `<div class="sankey-wrap"><svg class="chart" viewBox="0 0 ${w} ${h}" data-total="${total}" style="max-width:980px;display:block;margin:0 auto" role="img" aria-label="能流分析桑基图">
      ${ribbons}${nodes}</svg></div>`;
  }

  function bindSankey(scope) {
    const svg = scope.querySelector('.sankey-wrap svg');
    if (!svg) return;
    let tip = document.getElementById('sk-tip');
    if (!tip) { tip = document.createElement('div'); tip.id = 'sk-tip'; tip.className = 'sk-tip'; document.body.appendChild(tip); }
    const total = Number(svg.dataset.total) || 1;
    /* 悬浮提示：只用 DOM 文本节点写入，dataset 值绝不进 innerHTML（P1-5 存储型 XSS） */
    const setTip = (title, detail) => {
      const b = document.createElement('b');
      b.textContent = title;
      const span = document.createElement('span');
      span.textContent = detail;
      tip.replaceChildren(b, span);
    };
    const hide = () => { tip.classList.remove('show'); svg.classList.remove('sk-hover'); svg.querySelectorAll('.on').forEach(x => x.classList.remove('on')); };
    const move = (e) => { tip.style.left = Math.min(e.clientX + 14, innerWidth - 250) + 'px'; tip.style.top = Math.min(e.clientY + 14, innerHeight - 90) + 'px'; };
    svg.querySelectorAll('.sk-link').forEach(el => {
      el.addEventListener('mouseenter', () => {
        svg.classList.add('sk-hover'); el.classList.add('on');
        const v = Number(el.dataset.v) || 0;
        setTip(`${el.dataset.from} → ${el.dataset.to}`, `${fmtV(v, 2)} tce · 占比 ${(v / total * 100).toFixed(2)}%`);
        tip.classList.add('show');
      });
      el.addEventListener('mousemove', move);
      el.addEventListener('mouseleave', hide);
    });
    svg.querySelectorAll('.sk-node').forEach(g => {
      g.addEventListener('mouseenter', () => {
        svg.classList.add('sk-hover'); g.classList.add('on');
        setTip(g.dataset.name, `${fmtV(Number(g.dataset.v) || 0, 2)} tce · 占比 ${(Number(g.dataset.v) / total * 100).toFixed(2)}%`);
        tip.classList.add('show');
      });
      g.addEventListener('mousemove', move);
      g.addEventListener('mouseleave', hide);
    });
  }

  /* 供应链链路图 */
  function chain(steps) {
    const w = 760, h = 230, bw = 200, bh = 88, y = 66;
    const gapX = (w - steps.length * bw) / (steps.length + 1);
    return `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="供应链链路">
      ${steps.map((s, i) => {
        const x = gapX + i * (bw + gapX);
        const link = i < steps.length - 1
          ? `<line x1="${x + bw}" y1="${y + bh / 2}" x2="${x + bw + gapX}" y2="${y + bh / 2}" stroke="#cbd5e1" stroke-width="2"/>
             <circle cx="${x + bw + gapX / 2}" cy="${y + bh / 2}" r="5" fill="#fff" stroke="#cbd5e1" stroke-width="2"/>` : '';
        return `${link}<rect x="${x}" y="${y}" width="${bw}" height="${bh}" rx="8" fill="${s.color}"/>
          <text x="${x + bw / 2}" y="${y + 26}" text-anchor="middle" font-size="10.5" fill="#fff">${esc((s.name || '').slice(0, 12))}</text>
          <text x="${x + bw / 2}" y="${y + 41}" text-anchor="middle" font-size="10.5" fill="#fff">${esc((s.name || '').slice(12, 26))}</text>
          <text x="${x + bw / 2}" y="${y + 62}" text-anchor="middle" font-size="14" font-weight="700" fill="#fff">${esc(s.value)}</text>
          <text x="${x + bw / 2}" y="${y + 78}" text-anchor="middle" font-size="9.5" fill="#ffffffcc">${esc(s.sub || '')}</text>`;
      }).join('')}</svg>`;
  }

  /* 层级链路图：供应商按层级分列，父子之间连线，直观呈现供货链路 */
  function treeChart(nodes) {
    if (!nodes || !nodes.length) return '';
    const byId = new Map();
    nodes.forEach(n => byId.set(String(n.id), Object.assign({}, n, { children: [] })));
    const roots = [];
    byId.forEach(n => {
      const p = n.pid == null ? null : byId.get(String(n.pid));
      if (p) p.children.push(n); else roots.push(n);
    });
    if (!roots.length) return '';

    // 叶子按顺序占槽位，父节点纵向居中于其子节点，连线不会交叉
    let slots = 0;
    const place = (n, depth) => {
      n.depth = depth;
      if (!n.children.length) { n.slot = slots++; return; }
      n.children.forEach(c => place(c, depth + 1));
      n.slot = (n.children[0].slot + n.children[n.children.length - 1].slot) / 2;
    };
    roots.forEach(r => place(r, 0));

    const maxDepth = Math.max(...nodes.map(n => byId.get(String(n.id)).depth || 0));
    const rowCount = Math.max(1, slots);
    // 层级多时压缩节点宽度，保证整图不超过卡片内容宽度
    const NW = Math.max(112, Math.min(158, Math.floor((820 - 20 - maxDepth * 52) / (maxDepth + 1))));
    const NH = 54, GX = 52, GY = 14, PAD = 10;
    const w = PAD * 2 + (maxDepth + 1) * NW + maxDepth * GX;
    const h = PAD * 2 + rowCount * NH + (rowCount - 1) * GY;
    const xOf = d => PAD + d * (NW + GX);
    const yOf = n => PAD + n.slot * (NH + GY);

    const palette = ['#0f7a5a', '#2f9b78', '#63b79a', '#9ad2bd', '#c6e5d9'];
    const links = [], boxes = [];
    byId.forEach(n => {
      const x = xOf(n.depth), y = yOf(n), cy = y + NH / 2;
      n.children.forEach(c => {
        const x2 = xOf(c.depth), y2 = yOf(c) + NH / 2, x1 = x + NW;
        const dx = Math.max(16, (x2 - x1) / 2);
        links.push(`<path d="M${x1},${cy} C${x1 + dx},${cy} ${x2 - dx},${y2} ${x2},${y2}" fill="none" stroke="#cbd5e1" stroke-width="1.6" marker-end="url(#treeArrow)"/>`);
      });
      const bg = palette[Math.min(n.depth, palette.length - 1)];
      const fg = n.depth >= 3 ? '#0b3d2e' : '#ffffff';
      const name = String(n.name || '');
      // 公司全名普遍 10~14 字：先按可用宽度自适应字号，仍放不下才截断；层级角标挪到右下，把整行宽度让给名称
      const nameFont = Math.min(11, Math.max(9, (NW - 22) / Math.max(1, name.length)));
      const maxChars = Math.max(6, Math.floor((NW - 22) / 9));
      const short = name.length > maxChars ? name.slice(0, maxChars - 1) + '…' : name;
      const val = fmtV(Number(n.value) || 0, 2);
      boxes.push(`<g><title>${esc(name)}｜${val} ${esc(n.unit || '')}｜${esc(n.level || '')}</title>
        <rect x="${x}" y="${y}" width="${NW}" height="${NH}" rx="9" fill="${bg}"/>
        <text x="${x + 11}" y="${y + 22}" font-size="${nameFont.toFixed(1)}" font-weight="600" fill="${fg}">${esc(short)}</text>
        <text x="${x + 11}" y="${y + 41}" font-size="12.5" font-weight="700" fill="${fg}">${val}<tspan font-size="8.5" font-weight="400" opacity=".8"> ${esc(n.unit || '')}</tspan></text>
        <text x="${x + NW - 9}" y="${y + NH - 7}" text-anchor="end" font-size="8" fill="${fg}" opacity=".7">${esc(n.level || '')}</text></g>`);
    });
    return `<svg class="chart" viewBox="0 0 ${w} ${h}" style="max-height:${Math.min(h, 520)}px" role="img" aria-label="供应链层级链路">
      <defs><marker id="treeArrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#b7c6d4"/></marker></defs>
      ${links.join('')}${boxes.join('')}</svg>`;
  }

  /* 树形表：parent 行可折叠，末行为汇总行 */
  function treeTable(cfg) {
    const cols = cfg.cols;
    const head = `<thead><tr>${cols.map(c => `<th class="${c.num ? 'num' : ''}"${c.w ? ` style="width:${c.w}px"` : ''}>${esc(c.t)}</th>`).join('')}</tr></thead>`;
    const body = cfg.rows.map(r => {
      if (r.sum) return `<tr class="sum"><td><b>${esc(r.name)}</b></td><td class="num" colspan="${cols.length - 1}"><b>${fmtV(r.value, r.d ?? 2)}</b></td></tr>`;
      const indent = 8 + (r.depth || 0) * 18;
      const caret = r.children
        ? `<span class="caret" data-caret="${r.id}" style="display:inline-block;width:14px;cursor:pointer;color:#94a3b8">▾</span>`
        : `<span style="display:inline-block;width:14px"></span>`;
      return `<tr data-row="${r.id}"${r.parent ? ` data-parent="${r.parent}"` : ''}>
        <td><span style="display:inline-block;padding-left:${indent}px">${caret}${r.bold ? `<b>${esc(r.name)}</b>` : esc(r.name)}</span></td>
        ${cols.slice(1).map(c => `<td class="${c.num ? 'num' : ''}">${r[c.k] === undefined || r[c.k] === '' ? '—' : esc(typeof r[c.k] === 'number' ? fmtV(r[c.k], c.d ?? 2) : r[c.k])}</td>`).join('')}</tr>`;
    }).join('');
    return `<div class="table-wrap"><table class="tb tree">${head}<tbody>${body}</tbody></table></div>`;
  }

  const ANALYSIS_ENDPOINTS = {
    '能耗查询': '/analysis/energy-consumption',
    '碳排放查询': '/analysis/carbon-emission',
    '能效对标': '/analysis/energy-efficiency',
    '能流分析': '/analysis/energy-flow',
    '能源消费分析与策略推荐': '/analysis/strategy',
    '碳配额核算': '/analysis/quota',
    '碳足迹分析': '/analysis/footprint',
    '供应链碳管理分析': '/analysis/supply-chain'
  };
  const A_PALETTE = ['#0f7a5a', '#f59e0b', '#64748b', '#94a3b8', '#cbd5e1'];

  function analysisPage(cfg) {
    const st = { tab: 0, filters: {}, data: null, loading: false, error: '' };

    const panel = () => (cfg.tabPanels && cfg.tabPanels[st.tab]) ? Object.assign({}, cfg, cfg.tabPanels[st.tab]) : cfg;

    function defaultRange() {
      const f = (cfg.filters || []).find(x => x.type === 'monthRange') || {};
      return { from: f.from, to: f.to };
    }

    function queryString() {
      const dr = defaultRange();
      const from = st.filters.from || dr.from;
      const to = st.filters.to || dr.to;
      const qs = [];
      if (from) qs.push('from=' + encodeURIComponent(from));
      if (to) qs.push('to=' + encodeURIComponent(to));
      if (cfg.year) qs.push('year=' + encodeURIComponent(cfg.year));
      (cfg.filters || []).forEach(f => {
        if (!f.key || f.type === 'monthRange' || f.key === 'from' || f.key === 'to') return;
        const raw = st.filters[f.key];
        if (raw == null || raw === '') return;
        const val = typeof f.toParam === 'function' ? f.toParam(raw) : raw;
        if (val == null || val === '') return;
        qs.push(encodeURIComponent(f.param || f.key) + '=' + encodeURIComponent(val));
      });
      return qs.length ? '?' + qs.join('&') : '';
    }

    function liveCharts(d) {
      const out = [];
      if (d.sankey && (d.sankey.layers || []).length) {
        const layers = d.sankey.layers.map((layer, li) => layer.map(n => ({
          id: n.id, name: n.name, value: Number(n.value), color: A_PALETTE[li % A_PALETTE.length]
        })));
        out.push({ kind: 'sankey', title: '能流分布', sub: '总能耗 → 品种 → 设备', layers,
          links: (d.sankey.links || []).map(l => ({ from: l.from, to: l.to, value: Number(l.value) })) });
        return out;
      }
      const cols = (d.table && d.table.columns) || [], rows = (d.table && d.table.rows) || [];
      // 供应链页：按层级画链路图，一级 → 二级 → 三级，连线表示供货关系
      if (cfg.treeChart && rows.length) {
        out.push({
          kind: 'tree', title: cfg.trendTitle || '供应链链路', sub: '按层级展示供货关系', full: true,
          nodes: rows.map(r => ({
            id: r.id, pid: r.pid, name: r.supplier,
            value: Number(r.footprint) || 0, unit: r.unit, level: r.level
          }))
        });
      }
      const list = (d.series || []).filter(x => (x.values || []).length);
      if (!cfg.treeChart && list.length) {
        const s0 = list[0];
        const labels0 = s0.labels || [];
        // 按语义选图形：月份轴是趋势，用折线；离散分类（阶段/品种/产品/供应商）是对比，用条形。
        // 早先按"条目数 ≤ 6"判断，供应商一旦超过 6 家就被画成折线，离散点连成折线毫无意义。
        const timeAxis = labels0.length > 0 && labels0.every(l => /^\d{4}-\d{2}$/.test(l));
        if (s0.type === 'bar' && !timeAxis) {
          out.push({ kind: 'hbar', title: cfg.trendTitle || '构成', sub: s0.unit || '', unit: '',
            rows: labels0.map((l, i) => ({ name: l, value: Number((s0.values || [])[i]) || 0, color: A_PALETTE[0] })) });
        } else {
          out.push({ kind: 'line', title: cfg.trendTitle || '月度趋势', sub: s0.unit || '', labels: labels0,
            series: list.slice(0, 4).map((x, i) => ({ name: x.name, color: A_PALETTE[i % A_PALETTE.length],
              values: (x.values || []).map(Number) })), opts: { axisD: 1 } });
        }
      }
      // 名称列优先业务主体（产品/供应商/名称），避免退化成日期等辅助列
      const nameCol = ['product', 'supplier', 'name'].map(k => cols.find(c => c.key === k)).find(Boolean) || cols[0] || {};
      const nameKey = nameCol.key;
      const prefer = ['tce', 'emission', 'footprint', 'value', 'amount', 'qty'];
      const nums = cols.filter(c => c.type === 'number' && c.key !== 'share');
      const numCol = prefer.map(k => nums.find(c => c.key === k)).find(Boolean) || nums[0];
      if (numCol && rows.length) {
        out.push({ kind: 'hbar', title: cfg.tableChartTitle || '构成', sub: numCol.label, unit: '',
          rows: rows.slice(0, 8).map(r => ({ name: String(r[nameKey] == null ? '' : r[nameKey]),
            value: Number(r[numCol.key]) || 0, color: A_PALETTE[0] })) });
      }
      return out;
    }

    function liveTable(d) {
      if (!d.table) return null;
      return {
        title: d.table.title,
        cols: (d.table.columns || []).map(c => ({ k: c.key, t: c.label, num: c.type === 'number' })),
        rows: d.table.rows || []
      };
    }

    function liveView(v) {
      if (!st.data) return Object.assign({}, v, { kpis: [], info: null, charts: [], metrics: null, table: null });
      const d = st.data;
      return Object.assign({}, v, {
        kpis: (d.kpis || []).map(k => ({ label: k.label, value: k.value, unit: k.unit, foot: k.foot })),
        charts: liveCharts(d),
        metrics: null,
        info: null,
        table: liveTable(d)
      });
    }

    async function fetchOnly() {
      const ep = ANALYSIS_ENDPOINTS[cfg.title];
      if (!ep) return;
      st.loading = true; st.error = '';
      try {
        st.data = await CarbonAPI.request('GET', ep + queryString());
      } catch (e) {
        st.data = null; st.error = e.message;
      }
      st.loading = false;
    }

    return {
      title: cfg.title, group: cfg.group,
      render() {
        const fs = cfg.filters || [];
        const filterHTML = fs.map(f => {
          if (f.type === 'monthRange') {
            let end = '';
            if (f.to) { const [yy, mm] = String(f.to).split('-').map(Number); const last = new Date(yy, mm, 0).getDate(); end = f.to + '-' + String(last).padStart(2, '0'); }
            const from = st.filters.from || f.from, to = (st.filters.to || f.to);
            const endDate = to ? (() => { const [yy, mm] = String(to).split('-').map(Number); const last = new Date(yy, mm, 0).getDate(); return to + '-' + String(last).padStart(2, '0'); })() : end;
            return `<div class="field" style="min-width:240px"><label>${esc(f.label)}</label>${UI.rangePicker({ mode: 'date', from: from ? from + '-01' : '', to: endDate, ph: '开始日期 ~ 结束日期' })}</div>`;
          }
          return `<div class="field"><label>${esc(f.label)}</label>${UI.selectMenu({ key: f.key || '', value: st.filters[f.key] || '', options: ['全部', ...(typeof f.options === 'function' ? f.options() : f.options)] })}</div>`;
        }).join('');

        const view = liveView(panel());
        const tabs = cfg.tabs ? `<div class="tabs" id="a-tabs" style="margin:0 0 12px">${cfg.tabs.map((t, i) =>
          `<button data-tab="${i}" class="${i === st.tab ? 'active' : ''}">${esc(t)}</button>`).join('')}</div>` : '';

        const kpis = view.kpis ? `<div class="grid ${view.kpis.length >= 5 ? 'grid-5' : 'grid-' + Math.min(4, view.kpis.length)}" style="margin-bottom:13px">${view.kpis.map(k =>
          `<div class="kpi"><div class="kpi-label">${esc(k.label)}</div>
            <div class="kpi-value">${k.value}<small>${esc(k.unit || '')}</small></div>
            <div class="kpi-foot">${esc(k.foot || '')}</div></div>`).join('')}</div>` : '';

        const info = view.info ? `<section class="card" style="margin-bottom:13px"><div class="card-body">
          <div class="grid grid-3" style="gap:9px 22px">${view.info.map(([k, v]) => `<div class="small" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis"><span class="muted">${esc(k)}：</span><b>${esc(typeof v === 'function' ? v() : v)}</b></div>`).join('')}</div></div></section>` : '';

        const charts = (view.charts || []).map(c => {
          const full = view.charts.length === 1 || !!cfg.stackCharts;
          if (c.kind === 'donut') return chartShell(c.title, c.sub, `<div class="row" style="justify-content:center">${donut(c.data)}</div>
            <div class="stack" style="margin-top:8px">${c.data.map(m => `<div class="between small"><span><i style="display:inline-block;width:9px;height:9px;border-radius:3px;background:${m.color};margin-right:7px"></i>${esc(m.name)}</span><b class="mono">${m.value}%</b></div>`).join('')}</div>`, full);
          if (c.kind === 'hbar') return chartShell(c.title, c.sub, hBar(c.rows, c.unit), full);
          if (c.kind === 'line') return chartShell(c.title, c.sub, lineChart(c.labels, c.series, c.opts), full);
          if (c.kind === 'radar') return chartShell(c.title, c.sub, radar(c.axes, c.series), full);
          if (c.kind === 'sankey') return chartShell(c.title, c.sub, sankey(c.layers, c.links), full);
          if (c.kind === 'chain') return chartShell(c.title, c.sub, chain(c.steps), full);
          if (c.kind === 'tree') return chartShell(c.title, c.sub, treeChart(c.nodes), c.full || full);
          return '';
        }).join('');

        const metrics = view.metrics ? `<span class="card-sub">${view.metrics.map(m =>
          `${esc(m.label)} <b class="mono" style="color:var(--ink-900)">${esc(m.value)}</b>`).join('　')}</span>` : '';

        const table = view.table ? (() => {
          if (view.table.tree) return `<section class="card"><div class="card-head"><h2 class="card-title">${esc(view.table.title || '明细')}</h2>${metrics}
            <span class="card-sub">${view.table.rows.length} 行</span></div>${treeTable(view.table)}</section>`;
          const cols = view.table.cols;
          return `<section class="card"><div class="card-head"><h2 class="card-title">${esc(view.table.title || '明细')}</h2>${metrics}
            <span class="card-sub">${view.table.rows.length} 行</span></div>
            <div class="table-wrap"><table class="tb"><thead><tr>${cols.map(c => `<th class="${c.num ? 'num' : ''}">${esc(c.t)}</th>`).join('')}</tr></thead>
              <tbody>${view.table.rows.map(r => `<tr>${cols.map(c => `<td class="${c.num ? 'num' : ''}">${c.tag
                ? `<span class="tag ${c.tag(r)} dot">${esc(r[c.k])}</span>` : esc(r[c.k] ?? '—')}</td>`).join('')}</tr>`).join('')}</tbody></table></div>
            </section>`;
        })() : '';

        const banner = st.error ? `<div style="border:1px solid #F3D9A4;background:#FFFBEB;color:#7A4A05;border-radius:10px;padding:9px 12px;font-size:12.5px;margin-bottom:12px">数据加载失败：${esc(st.error)}</div>` : '';

        return `<div class="page">
          <div class="page-head"><div><h1 class="page-title">${cfg.title}</h1><p class="page-desc">${cfg.desc || ''}</p></div></div>
          <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
            ${filterHTML}
            <div class="field"><label>&nbsp;</label><div class="toolbar"><button class="btn btn-primary" id="a-query">查询</button><button class="btn" id="a-reset">重置</button></div></div>
          </div></div></section>
          ${tabs}${banner}${kpis}${info}
          ${charts ? `<div class="grid ${view.charts.length > 1 ? 'grid-2' : ''}" style="margin-bottom:13px">${charts}</div>` : ''}
          ${table}
        </div>`;
      },
      mount(root) {
        UI.bindRangePickers(root);
        UI.bindSelects(root);
        bindSankey(root);
        if (ANALYSIS_ENDPOINTS[cfg.title] && !st.data && !st.loading) {
          fetchOnly().then(() => UI.render());
        }
        $('#a-query').onclick = async (e) => {
          const rp = root.querySelector('[data-rp]');
          if (rp) {
            st.filters.from = (rp.dataset.from || '').slice(0, 7);
            st.filters.to = (rp.dataset.to || '').slice(0, 7);
          }
          root.querySelectorAll('[data-filter]').forEach(el => {
            const k = el.dataset.filter; if (k) st.filters[k] = el.value || '';
          });
          st.data = null;
          const done = busy(e.currentTarget, '查询中');
          await fetchOnly();
          done();
          UI.render();
          toast('ok', '查询完成');
        };
        $('#a-reset').onclick = () => {
          st.filters = {}; st.data = null;
          UI.render(); toast('ok', '已重置条件');
        };
        const t = $('#a-tabs');
        if (t) t.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => {
          const idx = Number(b.dataset.tab);
          if (idx === st.tab) return;
          st.tab = idx; st.data = null;
          UI.render();
        });
        root.querySelectorAll('[data-caret]').forEach(c => c.onclick = () => {
          const id = c.dataset.caret, open = c.textContent === '▾';
          c.textContent = open ? '▸' : '▾';
          root.querySelectorAll(`[data-parent="${id}"]`).forEach(r => { r.style.display = open ? 'none' : ''; });
        });
      }
    };
  }

  /* ================= 分析页面注册 ================= */
  route('/carbon-analysis/energy-consumption-query/index', analysisPage({
    title: '能耗查询', group: '能碳分析', desc: '按能源类别查询消费量与折标煤，汇总综合能耗',
    filters: [{ label: '日期范围', type: 'monthRange', from: monthRange().from, to: monthRange().to }],
  }));

  route('/carbon-analysis/energy-usage-management/index', analysisPage({
    title: '碳排放查询', group: '能碳分析', desc: '按能源类别核算排放量，含同比/环比与范围拆分',
    filters: [{ label: '日期范围', type: 'monthRange', from: monthRange().from, to: monthRange().to }],
  }));

  route('/carbon-analysis/energy-efficiency-benchmarking/index', analysisPage({
    title: '能效对标', group: '能碳分析', desc: '与行业标杆/基准对比，输出能效等级与建议',
    filters: [{ label: '日期范围', type: 'monthRange', from: monthRange().from, to: monthRange().to }],
  }));

  route('/carbon-analysis/energy-structure-analysis/index', analysisPage({
    title: '能流分析', group: '能碳分析', desc: '以桑基图拆解总能耗到能源品种的流向与占比',
    filters: [{ label: '日期范围', type: 'monthRange', from: monthRange().from, to: monthRange().to }],
  }));

  route('/carbon-analysis/energy-strategy-recommend/index', analysisPage({
    title: '能源消费分析与策略推荐', group: '能碳分析', desc: '能耗结构与消耗对比分析',
    filters: [{ label: '日期范围', type: 'monthRange', from: monthRange().from, to: monthRange().to }],
  }));

  route('/carbon-analysis/carbon-quota-calculation/index', analysisPage({
    title: '碳配额核算', group: '能碳分析', desc: '配额、排放与履约缺口的一屏核算',
    filters: [{ label: '日期范围', type: 'monthRange', from: new Date().getFullYear() + '-01', to: monthRange().to }],
  }));

  route('/carbon-footprint/product-footprint-analysis/index', analysisPage({
    title: '碳足迹分析', group: '碳足迹管理', desc: '按产品与年度对比足迹，定位热点阶段',
    trendTitle: '构成', tableChartTitle: '产品',
    filters: [{ label: '日期', type: 'year', key: 'year', param: 'year', options: (() => { const y = new Date().getFullYear(); return [String(y), String(y - 1)]; })() },
      { label: '产品名称', type: 'select', key: 'product', param: 'product', options: () => (DB.footprint || []).map(p => p.name) }],
  }));

  route('/carbon-footprint/supplier-footprint-analysis/index', analysisPage({
    title: '供应链碳管理分析', group: '碳足迹管理', desc: '沿供应链链路查看各环节碳足迹与传递关系',
    trendTitle: '供应链链路', treeChart: true, stackCharts: true,
    filters: [{ label: '供应商名称', type: 'select', key: 'supplier', param: 'supplier',
      // 只列一级供应商：选中一级即代表整条链，下级不再作为独立筛选项
      options: () => (DB.suppliers || []).filter(s => !s.parentId).map(s => s.name) },
      { label: '日期', type: 'year', key: 'year', param: 'year', options: (() => { const y = new Date().getFullYear(); return [String(y), String(y - 1)]; })() }],
  }));

})();
