/* 核心页面：首页 / 企业信息 / 碳排放核算 / 配额 / 足迹 / 报告三页 / 设备管理 / 预警设置 / 预警记录 / 在线监测 */
(function () {
    const localDate = (d = new Date()) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  const { $, $$, esc, num, int, icon, toast, modal, confirm, drawer, busy, sleep, trendChart, donut, barChart, pager, bindPager, route, go, state } = UI;
  const thisYear = () => new Date().getFullYear();
  const thisMonth = () => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0'); };
  const yearOptions = (n = 3) => Array.from({ length: n }, (_, i) => String(thisYear() - i));

  /* ============ 首页（对齐竞品：欢迎条 + 2 指标 + 2 图 + 待办/预警） ============ */

  function niceMax(raw, parts = 3) {
    const rough = raw / parts;
    const mag = Math.pow(10, Math.floor(Math.log10(rough || 1)));
    const n = rough / mag;
    const step = (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag;
    return step * parts;
  }
  function areaChart(rows, key, color = '#0c5a44') {
    const w = 700, h = 190, pad = { l: 44, r: 14, t: 14, b: 26 };
    const max = niceMax(Math.max(...rows.map(r => r[key])) * 1.06 || 1, 3);
    const x = i => pad.l + (i * (w - pad.l - pad.r)) / Math.max(1, rows.length - 1);
    const y = v => pad.t + (h - pad.t - pad.b) * (1 - v / max);
    const line = rows.map((r, i) => `${i ? 'L' : 'M'}${x(i)},${y(r[key])}`).join(' ');
    const area = `${line} L${x(rows.length - 1)},${h - pad.b} L${pad.l},${h - pad.b} Z`;
    const grid = [0, 1, 2, 3].map(k => {
      const yy = pad.t + ((h - pad.t - pad.b) * k) / 3;
      const v = max * (1 - k / 3);
      const lbl = v === 0 ? '0' : max >= 1000 ? (v / 1000).toFixed(1).replace(/\.0$/, '') + 'k' : v.toFixed(0);
      return `<line x1="${pad.l}" y1="${yy}" x2="${w - pad.r}" y2="${yy}" stroke="#eef2f6"/>
        <text x="${pad.l - 8}" y="${yy + 4}" text-anchor="end" font-size="10" fill="#94a3b8">${lbl}</text>`;
    }).join('');
    const dots = rows.map((r, i) => `<circle cx="${x(i)}" cy="${y(r[key])}" r="2.4" fill="#fff" stroke="${color}" stroke-width="1.6"/>`).join('');
    const labels = rows.map((r, i) => `<text x="${x(i)}" y="${h - 8}" text-anchor="middle" font-size="9.5" fill="#94a3b8">${esc(r.m)}</text>`).join('');
    return `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="趋势"><defs>
      <linearGradient id="ag" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color}" stop-opacity=".28"/><stop offset="1" stop-color="${color}" stop-opacity=".02"/></linearGradient></defs>
      ${grid}<path d="${area}" fill="url(#ag)"/><path d="${line}" fill="none" stroke="${color}" stroke-width="2"/>${dots}${labels}</svg>`;
  }

  route('/home', {
    title: '首页', group: '',
    render() {
      const k = DB.kpi;
      const now = new Date();
      const stamp = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')} ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
      const empt = (iconName, text) => `<div class="tb-empty" style="padding:34px 12px">${icon(iconName)}<div>${text}</div></div>`;
      return `<div class="page">
        <section class="card" style="margin-bottom:13px"><div class="card-body between" style="gap:14px">
          <div><h1 class="page-title" style="font-size:19px;margin:0 0 4px">欢迎使用，能碳管理平台</h1>
            <div class="small muted">${stamp}${DB.org.expireAt ? '　账户有效期：' + esc(DB.org.expireAt) : ''}</div></div>
          <div class="page-actions"><button class="btn" id="btn-refresh">${icon('refresh', 15)} 刷新</button></div>
        </div></section>

        <div class="grid grid-2" style="margin-bottom:13px">
          <div class="kpi"><div class="kpi-label">${thisYear()}年综合能耗(tce)</div>
            <div class="kpi-value">${num(k.energy, 2)}<small>tce</small></div>
            <div class="kpi-foot"><span class="delta down">▼ ${Math.abs(k.energyYoY)}%</span><span>较上年同期</span></div></div>
          <div class="kpi"><div class="kpi-label">${thisYear()}年碳排放总量(tCO₂e)</div>
            <div class="kpi-value">${num(k.emission, 2)}<small>tCO₂e</small></div>
            <div class="kpi-foot"><span class="delta down">▼ ${Math.abs(k.emissionYoY)}%</span><span>较上年同期</span></div></div>
        </div>

        <div class="grid grid-home" style="gap:13px">
          <div class="stack" style="gap:13px">
            <section class="card"><div class="card-head"><h2 class="card-title">近12月能源消费量(tce)</h2></div>
              <div class="card-body">${barChart(DB.monthly, 'energy', '#0c5a44', 'tce')}</div></section>
            <section class="card"><div class="card-head"><h2 class="card-title">近12月碳排放量(tCO₂e)</h2></div>
              <div class="card-body">${areaChart(DB.monthly, 'emission')}</div></section>
          </div>
          <div class="stack" style="gap:13px">
            <section class="card"><div class="card-head"><h2 class="card-title">待办通知</h2>
                <span class="tag tag-warn right">${DB.todos.length} 项</span></div>
              <div class="card-body stack" id="todo-list">
                ${DB.todos.length ? DB.todos.map(t => `<div class="between" style="padding:9px 0;border-bottom:1px dashed var(--line)">
                  <div style="min-width:0"><div style="font-size:13px;font-weight:600">${esc(t.title)}</div>
                    <div class="small muted">${esc(t.meta)}</div></div>
                  <button class="btn btn-sm" data-todo="${t.route}">${esc(t.action)}</button></div>`).join('') : empt('check', '暂无待办通知')}
              </div></section>
            <section class="card"><div class="card-head"><h2 class="card-title">预警记录</h2>
                <a class="right small" href="#/common-warning/record/list">查看全部</a></div>
              <div class="card-body">${DB.alertRecords.length ? `<div class="timeline">
                ${DB.alertRecords.slice(0, 4).map(a => `<div class="tl-item ${a.level === 'high' ? 'high' : a.level === 'mid' ? 'mid' : ''}">
                  <div class="tl-title">${esc(a.title)}</div><div class="tl-meta">${esc(a.value)} · ${esc(a.at)}</div></div>`).join('')}
                </div>` : empt('bell', '暂无预警记录')}</div></section>
          </div>
        </div>
      </div>`;
    },
    mount() {
      $('#btn-refresh').onclick = async (e) => { const done = busy(e.currentTarget, '刷新中'); await sleep(600); done(); toast('ok', '数据已刷新'); };
      $$('[data-todo]').forEach(b => b.onclick = () => go(b.dataset.todo));
    }
  });

  /* ============ 企业信息 ============ */
  const orgState = { data: null, loading: false };

  function orgEdit() {
    const v = orgState.data || {};
    const f = (k, label, type = 'text') => `<div class="field"><label>${label}</label><input class="input" name="${k}" type="${type}" value="${esc(v[k] == null ? '' : v[k])}"></div>`;
    modal({
      title: '编辑企业信息', width: 780,
      body: `<form id="org-form"><div class="grid grid-2" style="gap:0 14px">
        <div class="field"><label class="req">企业名称</label><input class="input" name="name" value="${esc(v.name || '')}"></div>
        ${f('shortName', '企业简称')}
        <div class="field"><label class="req">统一社会信用代码</label><input class="input" name="code" value="${esc(v.code || '')}"></div>
        ${f('orgType', '企业类型')}${f('legalPerson', '法定代表人')}${f('regDate', '注册日期', 'date')}
        ${f('regCapital', '注册资本')}${f('industry', '所属行业')}${f('scale', '企业规模')}${f('region', '所在地区')}
        ${f('contact', '联系人')}${f('phone', '联系人电话')}${f('email', '联系人邮箱', 'email')}
        <div class="field" style="grid-column:1/-1"><label>企业简介</label><textarea class="input" name="intro" rows="3">${esc(v.intro || '')}</textarea></div>
        <div class="field" style="grid-column:1/-1"><label>备注</label><input class="input" name="remark" value="${esc(v.remark || '')}"></div>
      </div></form>`,
      foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="org-save">保存</button>`,
      onMount(wrap, close) {
        wrap.querySelector('#org-save').onclick = async (e) => {
          const d = Object.fromEntries(new FormData(wrap.querySelector('#org-form')));
          if (!d.name || !d.code) { toast('err', '企业名称与统一社会信用代码必填'); return; }
          const done = busy(e.currentTarget, '保存中');
          try {
            const body = Object.assign({}, d, {
              regDate: d.regDate || null, version: v.version,
              logoFileId: v.logoFileId == null ? null : v.logoFileId,
              licenseFileId: v.licenseFileId == null ? null : v.licenseFileId
            });
            orgState.data = await CarbonAPI.request('PUT', '/org', body);
            done(); close(); toast('ok', '企业信息已保存'); UI.render();
          } catch (err) { done(); toast('err', err.message); }
        };
      }
    });
  }

  route('/enterprise/info', {
    title: '企业信息', group: '企业管理',
    render() {
      const a = orgState.data, o = DB.org || {};
      const v = (k) => (a ? (a[k] == null ? '' : a[k]) : (o[k] == null ? '' : o[k]));
      const rows = [
        ['企业名称', v('name')], ['企业简称', a ? v('shortName') : v('short')],
        ['企业类型', v('orgType')], ['统一社会信用代码', v('code')],
        ['法定代表人', v('legalPerson')], ['注册日期', v('regDate')],
        ['注册资本', v('regCapital')], ['所属行业', v('industry')],
        ['企业规模', v('scale')], ['所在地区', v('region')],
        ['联系人', v('contact')], ['联系人电话', v('phone')], ['联系人邮箱', v('email')],
        ['企业简介', v('intro')], ['备注', v('remark')]
      ];
      const canEdit = (CarbonAPI.me() && CarbonAPI.me().role === 'admin');
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">企业信息</h1>
          <p class="page-desc">企业档案由平台管理员维护；其他成员只读</p></div>
          ${canEdit ? `<div class="page-actions"><button class="btn btn-primary" id="org-edit">${icon('gear', 15)} 编辑</button></div>` : ''}</div>
        <section class="card"><div class="card-head"><h2 class="card-title">企业信息</h2>
          <span class="tag tag-muted">${canEdit ? '可编辑' : '只读'}</span></div>
          <div class="table-wrap"><table class="tb detail">
            <tbody>${rows.map(([k, val]) => `<tr><th style="width:180px;background:#f8fafb">${esc(k)}</th><td>${esc(val || '—')}</td></tr>`).join('')}</tbody>
          </table></div>
        </section></div>`;
    },
    mount(root) {
      if (!orgState.data && !orgState.loading) {
        orgState.loading = true;
        CarbonAPI.request('GET', '/org')
          .then(v => { orgState.data = v; orgState.loading = false; UI.render(); })
          .catch(() => { orgState.loading = false; });
      }
      const b = root.querySelector('#org-edit');
      if (b) b.onclick = () => orgEdit();
    }
  });

  /* ============ 碳排放核算 ============ */
  const acState = { page: 1, size: 10, sort: { k: 'period', dir: 'desc' }, kw: '', from: '', to: '', status: '', sel: new Set(), rows: DB.accounting.slice() };
  /** 原始状态 → 旧版展示状态（bootstrap 快照里的 status 字段口径，保留兼容）。 */
  const acLegacyStatus = (s) => (s === 'locked' || s === 'submitted') ? 'done' : (s === 'calculated' ? 'review' : 'draft');
  let acSyncing = false;
  /**
   * 进入核算列表时的轻量状态刷新（BUG-002）。
   * <p>
   * acState.rows 初始来自登录时的 bootstrap 快照；若期间被他人推进（例如已锁定），
   * 旧会话会一直显示「草稿 + 计算」，直到手动刷新。这里只拉「期间 / 状态 / 版本」三个字段合并回来，
   * 不重跑全量 bootstrap；状态实际变化才重绘，避免打断用户正在进行的操作。
   * 只发 GET，不产生任何写操作（幂等断言 I01/I03 不受影响）。
   */
  function acSyncStatus() {
    const api = window.CarbonAPI || {};
    if (!api.request || acSyncing) return;
    acSyncing = true;
    api.request('GET', '/accounting-periods')
      .then((list) => {
        acSyncing = false;
        if (!Array.isArray(list) || !list.length) return;
        const byPeriod = new Map(list.map(x => [x.period, x]));
        let changed = false;
        acState.rows.forEach((r) => {
          const s = byPeriod.get(r.period);
          if (!s) return;
          const rawStatus = s.status || 'draft';
          const version = s.version == null ? r.version : s.version;
          if (r.rawStatus !== rawStatus || r.version !== version) changed = true;
          r.rawStatus = rawStatus;
          r.version = version;
          r.status = acLegacyStatus(rawStatus);
          if (s.calcVersion != null) r.calcVersion = s.calcVersion;
        });
        if (!changed) return;
        // 同步回全局快照，首页/详情等复用同一份数据时不会出现两套状态
        (DB.accounting || []).forEach((d) => {
          const r = acState.rows.find(x => x.period === d.period);
          if (!r) return;
          d.rawStatus = r.rawStatus; d.version = r.version; d.status = r.status;
        });
        const host = $('#ac-table');
        if (host) acRefresh(host);
      })
      .catch(() => { acSyncing = false; });
  }
  const acFiltered = () => {
    let rows = acState.rows.slice();
    if (acState.kw) rows = rows.filter(r => r.id.toLowerCase().includes(acState.kw.toLowerCase()) || r.period.includes(acState.kw));
    if (acState.from) rows = rows.filter(r => r.period >= acState.from);
    if (acState.to) rows = rows.filter(r => r.period <= acState.to);
    if (acState.status) rows = rows.filter(r => r.status === acState.status);
    rows.sort((a, b) => { const c = typeof a[acState.sort.k] === 'number' ? a[acState.sort.k] - b[acState.sort.k] : String(a[acState.sort.k]).localeCompare(String(b[acState.sort.k])); return acState.sort.dir === 'asc' ? c : -c; });
    return rows;
  };
  const stTag = (s) => {
    switch (s) {
      case 'locked': return '<span class="tag tag-ok dot">已锁定</span>';
      case 'approved': return '<span class="tag tag-ok dot">已批准</span>';
      case 'reviewed': return '<span class="tag tag-info dot">已复核</span>';
      case 'submitted': return '<span class="tag tag-info dot">已提交</span>';
      case 'calculated': return '<span class="tag tag-warn dot">已计算</span>';
      case 'done': return '<span class="tag tag-ok dot">已归档</span>';
      case 'review': return '<span class="tag tag-warn dot">待复核</span>';
      default: return '<span class="tag tag-muted dot">草稿</span>';
    }
  };

  function acActions(r) {
    const s = r.rawStatus || 'draft';
    const api = window.CarbonAPI || {};
    const admin = api.isAdmin && api.isAdmin();
    const fill = api.canWrite && api.canWrite();
    const canRev = api.canReview && api.canReview();
    const canApp = api.canApprove && api.canApprove();
    const btn = (attr, text, cls) => `<button class="btn btn-sm ${cls || ''}" ${attr}="${r.period}">${text}</button>`;
    const view = btn('data-view', '查看', 'btn-ghost');
    if (s === 'locked') return view + (admin ? btn('data-unlock', '解锁') : '');
    if (s === 'approved') return view + (admin ? btn('data-lock', '锁定', 'btn-primary') : '');
    if (s === 'reviewed') return view + (canApp ? btn('data-approve', '批准', 'btn-primary') : '');
    if (s === 'submitted') return view + (canRev ? btn('data-review', '复核', 'btn-primary') : '');
    if (s === 'calculated') return view + (fill ? btn('data-submit', '提交', 'btn-primary') + btn('data-calc', '重算') : '');
    return view + (fill ? btn('data-calc', '计算', 'btn-primary') : '');
  }

  function acTable() {
    const all = acFiltered(), rows = all.slice((acState.page - 1) * acState.size, acState.page * acState.size);
    return `<div class="table-wrap"><table class="tb"><thead><tr>
      <th>核算日期</th>
      <th>状态</th>
      <th>温室气体量</th>
      <th>温室气体单位</th>
      <th>能源量</th>
      <th>能源单位</th>
      <th>核算标准名称</th><th>核算标准简称</th><th>核算标准编码</th><th>企业</th>
      <th style="width:120px">操作</th></tr></thead>
      <tbody>${rows.length ? rows.map(r => `<tr>
        <td><b>${r.period}</b><div class="small muted">${r.id}</div></td>
        <td>${stTag(r.rawStatus || r.status)}</td>
        <td class="num"><b>${num(r.total, 1)}</b></td><td>tCO₂e</td>
        <td class="num">${num(r.energy, 1)}</td><td>tce</td>
        <td>GB/T 32151.9-2024 工业其他行业</td><td>GB/T 32151.9</td><td>GB/T32151.9-2024</td>
        <td>${esc(DB.org.short)}</td>
        <td class="actions">${acActions(r)}</td>
      </tr>`).join('') : `<tr><td colspan="11"><div class="tb-empty">${icon('ledger')}<div>没有匹配的核算记录</div><div class="small">试试清空筛选条件</div></div></td></tr>`}</tbody></table></div>
      ${pager(all.length, acState.page, acState.size)}`;
  }

  function acRefresh(host) {
    host.innerHTML = acTable();
    bindAc(host);
    syncBulk();
  }
  function syncBulk() {
    const n = acState.sel.size;
    const tip = $('#ac-sel'); if (tip) tip.textContent = '已选 ' + n + ' 项';
  }
  function bindAc(host) {
    const byPeriod = (p) => acState.rows.find(x => x.period === p) || {};
    host.querySelectorAll('[data-view]').forEach(b => b.onclick = () => acDetail(b.dataset.view));
    host.querySelectorAll('[data-calc]').forEach(b => b.onclick = async (e) => {
      const done = busy(e.currentTarget, '核算中');
      try {
        await CarbonAPI.request('POST', '/accounting-periods/' + b.dataset.calc + '/calculate');
        done(); toast('ok', '核算完成', b.dataset.calc);
        await CarbonAPI.refresh();
      } catch (err) { done(); toast('err', err.message); }
    });
    host.querySelectorAll('[data-submit]').forEach(b => b.onclick = async (e) => {
      const r = byPeriod(b.dataset.submit);
      const done = busy(e.currentTarget, '提交中');
      try {
        await CarbonAPI.request('POST', '/accounting-periods/' + r.period + '/submit', { version: r.version });
        done(); toast('ok', '提交成功', r.period);
        await CarbonAPI.refresh();
      } catch (err) { done(); toast('err', err.message); }
    });
    host.querySelectorAll('[data-review]').forEach(b => b.onclick = async (e) => {
      const r = byPeriod(b.dataset.review);
      const ok = await confirm({ title: '复核通过', content: `确认 ${r.period} 的计算结果与明细无误？复核后进入批准环节。`, okText: '复核通过' });
      if (!ok) return;
      const done = busy(e.currentTarget, '复核中');
      try {
        await CarbonAPI.request('POST', '/accounting-periods/' + r.period + '/review', { version: r.version });
        done(); toast('ok', '复核通过', r.period);
        await CarbonAPI.refresh();
      } catch (err) { done(); toast('err', err.message); }
    });
    host.querySelectorAll('[data-approve]').forEach(b => b.onclick = async (e) => {
      const r = byPeriod(b.dataset.approve);
      const ok = await confirm({ title: '批准', content: `批准 ${r.period} 的核算结果？批准后由管理员锁定。`, okText: '批准' });
      if (!ok) return;
      const done = busy(e.currentTarget, '批准中');
      try {
        await CarbonAPI.request('POST', '/accounting-periods/' + r.period + '/approve', { version: r.version });
        done(); toast('ok', '已批准', r.period);
        await CarbonAPI.refresh();
      } catch (err) { done(); toast('err', err.message); }
    });
    host.querySelectorAll('[data-lock]').forEach(b => b.onclick = async (e) => {
      const r = byPeriod(b.dataset.lock);
      const done = busy(e.currentTarget, '锁定中');
      try {
        await CarbonAPI.request('POST', '/accounting-periods/' + r.period + '/lock', { version: r.version });
        done(); toast('ok', '已锁定', r.period);
        await CarbonAPI.refresh();
      } catch (err) { done(); toast('err', err.message); }
    });
    host.querySelectorAll('[data-unlock]').forEach(b => b.onclick = () => {
      const r = byPeriod(b.dataset.unlock);
      modal({
        title: '解锁 · ' + r.period, width: 460,
        body: `<div class="field"><label class="req">解锁原因</label><textarea class="input" id="ac-reason" rows="3" placeholder="解锁会记入审计，请说明原因"></textarea><div class="err-msg"></div></div>
          <div class="small muted">锁定期间禁止任何写入，解锁后重新开放。</div>`,
        foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="ac-unlock-ok">确认解锁</button>`,
        onMount(wrap, close) {
          wrap.querySelector('#ac-unlock-ok').onclick = async (e) => {
            const reason = wrap.querySelector('#ac-reason').value.trim();
            if (!reason) { toast('err', '请填写解锁原因'); return; }
            const done = busy(e.currentTarget, '解锁中');
            try {
              await CarbonAPI.request('POST', '/accounting-periods/' + r.period + '/unlock', { version: r.version, reason });
              done(); close(); toast('ok', '已解锁', r.period);
              await CarbonAPI.refresh();
            } catch (err) { done(); toast('err', err.message); }
          };
        }
      });
    });
    bindPager(host, n => { acState.page = n; acRefresh(host); });
  }

  function acDetail(period) {
    const rec = acState.rows.find(r => r.period === period) || {};
    CarbonAPI.request('GET', '/accounting-periods/' + period).then(v => {
      const lines = v.lines || [];
      const kpi = (label, value, unit) => `<div class="kpi"><div class="kpi-label">${esc(label)}</div>
        <div class="kpi-value">${esc(String(value == null ? '—' : value))}<small>${esc(unit)}</small></div></div>`;
      const body = `<div class="grid grid-4" style="margin-bottom:12px">
          ${kpi('范围一', num(v.scope1, 2), 'tCO₂e')}${kpi('范围二', num(v.scope2, 2), 'tCO₂e')}
          ${kpi('合计', num(v.total, 2), 'tCO₂e')}${kpi('核算版本', 'v' + (v.calcVersion || 0), '')}
        </div>
        <div class="table-wrap" style="max-height:400px;overflow:auto"><table class="tb"><thead><tr>
          <th>设备</th><th>范围</th><th class="num">活动数据</th><th>单位</th>
          <th class="num">排放因子</th><th>因子版本</th><th class="num">排放量(tCO₂e)</th>
        </tr></thead><tbody>${lines.length ? lines.map(l => `<tr>
          <td>${esc(l.deviceName || '')}</td><td>${esc(l.scopeType || '')}</td>
          <td class="num">${num(l.activity, 2)}</td><td>${esc(l.activityUnit || '')}</td>
          <td class="num">${num(l.factorValue, 4)}</td><td>${esc(l.factorVersion || '—')}</td>
          <td class="num"><b>${num(l.emission, 3)}</b></td></tr>`).join('')
          : '<tr><td colspan="7"><div class="tb-empty">该期间没有排放明细</div></td></tr>'}</tbody></table></div>
        <div class="small muted" style="margin-top:8px">状态：${esc(v.status || '')}　记录版本：${v.version}　锁定人：${esc(v.lockedBy || '—')}</div>`;
      modal({ title: '核算明细 · ' + period, width: 980, body, foot: `<button class="btn" data-close>关闭</button>` });
    }).catch(e => toast('err', e.message));
  }

  function acForm(rec) {
    const editing = !!rec;
    const now = new Date();
    const def = editing ? rec.period : `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    modal({
      title: editing ? '重算 · ' + def : '新建核算', width: 500,
      body: `<form id="ac-form">
        <div class="field" data-f="period"><label class="req">核算月份</label>${UI.formMonth('period', def)}<div class="err-msg"></div></div>
        <div class="small muted" style="margin-top:4px;line-height:1.7">系统按该月设备读数与已审核因子实时计算，生成新的核算版本；已锁定的期间不能重算。</div>
      </form>`,
      foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="ac-save">${editing ? '重新计算' : '开始核算'}</button>`,
      onMount(wrap, close) {
        UI.bindMonthPickers(wrap);
        const form = wrap.querySelector('#ac-form');
        wrap.querySelector('#ac-save').onclick = async (e) => {
          const period = new FormData(form).get('period');
          if (!period) { toast('err', '请选择核算月份'); return; }
          const done = busy(e.currentTarget, '核算中');
          try {
            await CarbonAPI.request('POST', '/accounting-periods/' + period + '/calculate');
            done(); close(); toast('ok', '核算完成', period);
            await CarbonAPI.refresh();
          } catch (err) { done(); toast('err', err.message); }
        };
      }
    });
  }

  route('/carbon-computation/calculation-complete/list', {
    title: '碳排放核算', group: '碳排放核算',
    render() {
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">碳排放核算</h1>
          <p class="page-desc">按核算期间归集范围一/范围二排放，提交后进入复核流程</p></div>
          <div class="page-actions">
          <button class="btn btn-primary" id="ac-new">${icon('plus', 15)} 新建</button></div></div>
        <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
          <div class="field" style="min-width:236px" data-acrange><label>核算日期</label>${UI.rangePicker({ mode: 'month', ph: '开始月份 ~ 结束月份' })}</div>
          <div class="field"><label>&nbsp;</label><div class="toolbar"><button class="btn btn-primary" id="ac-search">查询</button><button class="btn" id="ac-reset">重置</button></div></div>
        </div></div></section>
        <section class="card"><div class="card-head"><h2 class="card-title">核算记录</h2>
          <span class="card-sub">共 ${acState.rows.length} 个期间</span></div>
          <div id="ac-table">${acTable()}</div></section>
      </div>`;
    },
    mount() {
      if (Array.isArray(DB.accounting)) acState.rows = DB.accounting.slice();
      acState.sel.clear();
      const acHost = $('#ac-table');
      if (acHost) acHost.innerHTML = acTable();
      bindAc($('#ac-table')); syncBulk();
      // 快照可能已过期：进入页面先按服务端最新状态校正一次（只读，不写）
      acSyncStatus();
      UI.bindRangePickers($('#view'));
      $('#ac-search').onclick = () => {
        const box = $('#view').querySelector('[data-acrange] [data-rp]');
        acState.from = box ? box.dataset.from || '' : ''; acState.to = box ? box.dataset.to || '' : '';
        acState.page = 1; acRefresh($('#ac-table')); toast('ok', '查询完成', acFiltered().length + ' 条记录');
      };
      $('#ac-reset').onclick = () => {
        const box = $('#view').querySelector('[data-acrange] [data-rp]');
        if (box) { box.dataset.from = ''; box.dataset.to = ''; box.querySelector('input').value = ''; }
        acState.kw = ''; acState.from = ''; acState.to = ''; acState.page = 1; acState.sel.clear(); acRefresh($('#ac-table'));
      };
      $('#ac-new').onclick = () => acForm();
    }
  });

  /* ============ 碳排放配额 ============ */
  function qChart(rows) {
    const w = 940, h = 260, pad = { l: 44, r: 16, t: 26, b: 46 };
    const max = niceMax(Math.max(...rows.flatMap(r => [r.total, r.next]), 1) * 1.08, 3);
    const gap = (w - pad.l - pad.r) / Math.max(rows.length, 1);
    const bw = Math.min(26, gap * 0.28);
    const y = v => pad.t + (h - pad.t - pad.b) * (1 - v / max);
    const grid = [0, 1, 2, 3].map(k => {
      const yy = pad.t + ((h - pad.t - pad.b) * k) / 3, v = max * (1 - k / 3);
      const lbl = v === 0 ? '0' : v >= 10000 ? (v / 10000).toFixed(1).replace(/\.0$/, '') + '万' : v.toFixed(0);
      return `<line x1="${pad.l}" y1="${yy}" x2="${w - pad.r}" y2="${yy}" stroke="#eef2f6"/>
        <text x="${pad.l - 8}" y="${yy + 4}" text-anchor="end" font-size="9.5" fill="#94a3b8">${lbl}</text>`;
    }).join('');
    const bars = rows.map((r, i) => {
      const cx = pad.l + gap * i + gap / 2;
      const a = `<rect x="${cx - bw - 3}" y="${y(r.total)}" width="${bw}" height="${h - pad.b - y(r.total)}" fill="#0f7a5a" rx="2"/>`;
      const b = r.next ? `<rect x="${cx + 3}" y="${y(r.next)}" width="${bw}" height="${h - pad.b - y(r.next)}" fill="#65a30d" rx="2"/>` : '';
      const t1 = r.total ? `<text x="${cx - bw / 2 - 3}" y="${y(r.total) - 4}" text-anchor="middle" font-size="9" fill="#475569">${r.total}</text>` : '';
      const t2 = r.next ? `<text x="${cx + bw / 2 + 3}" y="${y(r.next) - 4}" text-anchor="middle" font-size="9" fill="#475569">${r.next}</text>` : '';
      const lab = `<text x="${cx}" y="${h - pad.b + 16}" text-anchor="middle" font-size="9.5" fill="#94a3b8" transform="rotate(-24 ${cx} ${h - pad.b + 16})">配额 (${r.year}年)</text>`;
      return a + b + t1 + t2 + lab;
    }).join('');
    return `<div class="row small" style="gap:16px;margin-bottom:4px;justify-content:center">
        <span><i style="display:inline-block;width:10px;height:10px;border-radius:2px;background:#0f7a5a;margin-right:5px"></i>配额量</span>
        <span><i style="display:inline-block;width:10px;height:10px;border-radius:2px;background:#65a30d;margin-right:5px"></i>下年度配额量</span></div>
      <svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="配额图表">${grid}${bars}</svg>`;
  }

  const qState = { custom: [], rows: null, loading: false, sel: new Set(), from: '', to: '', view: 'table' };
  const qRows = () => (qState.rows ? qState.rows : DB.quota.history.concat(qState.custom));

  route('/carbon-emission/quota/list', {
    title: '碳排放配额', group: '预算管理',
    render() {
      const from = qState.from, to = qState.to;
      const rows = qRows().filter(r => (!from || r.year >= Number(from)) && (!to || r.year <= Number(to)));
      const sel = qState.sel;
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">碳排放配额</h1>
          <p class="page-desc">年度配额分配、履约进度与下年度预测量</p></div></div>

        <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
          <div class="field" style="min-width:236px" data-qrange><label>年度</label>${UI.rangePicker({ mode: 'year', from: from ? String(from) : '', to: to ? String(to) : '', ph: '开始年份 ~ 结束年份' })}</div>
          <div class="field"><label>&nbsp;</label><div class="toolbar">
            <button class="btn btn-primary" id="q-search">查询</button><button class="btn" id="q-reset">重置</button></div></div>
        </div></div></section>

        <section class="card"><div class="card-head">
          <div class="toolbar">
            <button class="btn btn-primary" id="q-new">${icon('plus', 15)} 新建</button>
            <button class="btn btn-danger" id="q-bulk" ${sel.size ? '' : 'disabled'}>批量删除${sel.size ? '（' + sel.size + '）' : ''}</button>
            <button class="btn" id="q-view">${icon('chart', 15)} ${qState.view === 'chart' ? '切换表格' : '切换图表'}</button>
          </div>
          <span class="card-sub right">已选 ${sel.size} 项</span></div>
          <div class="card-body" id="q-body">${qState.view === 'chart'
            ? `${qChart(rows.map(r => ({ year: r.year, total: r.total, next: r.next || (r.year === (DB.quota.year || thisYear()) ? DB.quota.next : 0) })))}
               <div class="between small" style="margin-top:6px"><span class="muted">共 ${rows.length} 条</span><span class="muted">每页 10 条</span></div>`
            : `<div class="table-wrap"><table class="tb"><thead><tr>
              <th style="width:34px"><input type="checkbox" data-q-all></th>
              <th>年度</th><th>配额量</th><th>配额量单位</th><th>下年度配额量</th><th>备注</th><th>企业</th><th style="width:130px">操作</th></tr></thead>
              <tbody>${rows.length ? rows.map(r => `<tr>
                <td><input type="checkbox" data-q-check="${r.year}" ${sel.has(String(r.year)) ? 'checked' : ''}></td>
                <td><b>${r.year}年</b></td><td class="num">${int(r.total)}</td><td>tCO₂e</td>
                <td class="num">${r.next ? int(r.next) : (r.year === (DB.quota.year || thisYear()) ? int(DB.quota.next) : '')}</td>
                <td>${r.note ? esc(r.note) : '<span class="muted">—</span>'}</td><td>${esc(DB.org.short)}</td>
                <td class="actions"><button class="btn btn-sm" data-q-edit="${r.year}">编辑</button>
                  <button class="btn btn-sm btn-ghost" data-q-del="${r.year}">删除</button></td></tr>`).join('')
                : `<tr><td colspan="8"><div class="tb-empty">${icon('wallet')}<div>暂无配额记录</div></div></td></tr>`}</tbody></table></div>
               ${pager(rows.length, 1, 10)}`}
          </div></section>
      </div>`;
    },
    mount(root) {
      if (!qState.rows && !qState.loading) {
        qState.loading = true;
        CarbonAPI.request('GET', '/quota-years').then(list => {
          qState.rows = list.map(q => ({
            dbId: q.id, year: q.quotaYear, total: q.totalQty, next: q.nextYearQty,
            note: q.note, version: q.version, used: q.used
          }));
          qState.loading = false; UI.render();
        }).catch(() => { qState.loading = false; });
      }
      const sel = qState.sel;
      const syncBulk = () => {
        const b = $('#q-bulk');
        if (b) { b.disabled = sel.size === 0; b.textContent = '批量删除' + (sel.size ? '（' + sel.size + '）' : ''); }
      };
      root.querySelectorAll('[data-q-check]').forEach(c => c.onchange = () => {
        c.checked ? sel.add(c.dataset.qCheck) : sel.delete(c.dataset.qCheck); syncBulk();
      });
      const all = root.querySelector('[data-q-all]');
      if (all) all.onchange = () => {
        root.querySelectorAll('[data-q-check]').forEach(c => {
          c.checked = all.checked;
          all.checked ? sel.add(c.dataset.qCheck) : sel.delete(c.dataset.qCheck);
        });
        syncBulk();
      };
      syncBulk();

      $('#q-view').onclick = () => { qState.view = qState.view === 'chart' ? 'table' : 'chart'; UI.render(); toast('ok', '已切换为' + (qState.view === 'table' ? '表格' : '图表') + '视图'); };
      UI.bindRangePickers($('#view'));
      $('#q-search').onclick = () => {
        const box = $('#view').querySelector('[data-qrange] [data-rp]');
        qState.from = box ? box.dataset.from || '' : ''; qState.to = box ? box.dataset.to || '' : '';
        sel.clear(); UI.render(); toast('ok', '查询完成');
      };
      $('#q-reset').onclick = () => {
        const box = $('#view').querySelector('[data-qrange] [data-rp]');
        if (box) { box.dataset.from = ''; box.dataset.to = ''; box.querySelector('input').value = ''; }
        qState.from = ''; qState.to = ''; sel.clear(); UI.render();
      };

      const openForm = (rec) => {
        const editing = !!rec;
        modal({ title: editing ? '编辑' : '添加', width: 720,
          body: `<form id="q-form">
            <div class="grid grid-2" style="gap:12px 14px">
              <div class="field" data-f="year"><label class="req">年度</label>
                <div class="pick" id="q-year-pick">
                  <input class="input" name="year" value="${editing ? rec.year : ''}" placeholder="选择年度" readonly>
                  <span class="pick-ico"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="3" y="5" width="18" height="16" rx="3"/><path d="M8 3v4M16 3v4M3 10h18"/></svg></span>
                  <div class="pick-pop" id="q-year-pop">
                    <div class="pick-head"><button type="button" data-dec="-1" aria-label="上一组">‹</button><b id="q-dec"></b><button type="button" data-dec="1" aria-label="下一组">›</button></div>
                    <div class="pick-grid" id="q-years"></div>
                  </div>
                </div>
                <div class="err-msg"></div></div>
              <div class="field"><label>备注</label><input class="input" name="note" placeholder="备注" value="${editing ? esc(rec.note || '') : ''}"></div>
              <div class="field" data-f="total"><label class="req">配额量</label>
                <div class="input-group"><input class="input" name="total" type="number" value="${editing ? rec.total : ''}" placeholder="配额量"><span class="unit">tCO₂e</span></div><div class="err-msg"></div></div>
              <div class="field"><label>下年度配额</label>
                <div class="input-group"><input class="input" name="next" type="number" value="${editing ? (rec.next || '') : ''}" placeholder="默认与本年配额量一致，可修改"><span class="unit">tCO₂e</span></div></div>
            </div>
          </form>`,
          foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="q-save">保存</button>`,
          onMount(wrap, close) {
            const form = wrap.querySelector('#q-form');
            const inp = form.querySelector('[name="year"]');
            const pop = wrap.querySelector('#q-year-pop'), grid = wrap.querySelector('#q-years'), decEl = wrap.querySelector('#q-dec');
            const setE = (n, m) => { const f = form.querySelector(`[data-f="${n}"]`); f.classList.toggle('error', !!m); f.querySelector('.err-msg').textContent = m || ''; };
            let ds = Math.floor((Number(inp.value) || thisYear()) / 10) * 10;
            const paint = () => {
              decEl.textContent = ds + ' – ' + (ds + 9);
              grid.innerHTML = Array.from({ length: 10 }, (_, i) => ds + i).map(y =>
                `<button type="button" data-y="${y}" class="${Number(inp.value) === y ? 'on' : ''}">${y}</button>`).join('');
              grid.querySelectorAll('[data-y]').forEach(b => b.onclick = () => {
                inp.value = b.dataset.y; pop.classList.remove('open'); setE('year', '');
              });
            };
            if (!editing) {
              inp.onclick = () => { paint(); pop.classList.toggle('open'); };
              wrap.querySelectorAll('[data-dec]').forEach(b => b.onclick = () => { ds += Number(b.dataset.dec) * 10; paint(); });
              wrap.addEventListener('click', e => { if (!e.target.closest('#q-year-pick')) pop.classList.remove('open'); });
            } else { inp.style.cursor = 'default'; }
            wrap.querySelector('#q-save').onclick = async (e) => {
              const d = Object.fromEntries(new FormData(form));
              let bad = false;
              const y = editing ? rec.year : Number(d.year), t = Number(d.total);
              setE('year', ''); setE('total', '');
              if (!editing && (!y || y < 2020 || y > 2100)) { setE('year', '年度 必填'); bad = true; }
              if (!editing && y && qRows().some(r => r.year === y)) { setE('year', '该年度已存在配额记录'); bad = true; }
              if (!t || t <= 0) { setE('total', '配额量 必填'); bad = true; }
              if (bad) return;
              const next = d.next ? Number(d.next) : t;
              const done = busy(e.currentTarget, '保存中');
              try {
                const body = { year: y, total: t, next, note: d.note || '' };
                if (editing) { body.version = rec.version; await CarbonAPI.request('PUT', '/quota-years/' + rec.dbId, body); }
                else await CarbonAPI.request('POST', '/quota-years', body);
                done(); close(); toast('ok', editing ? '配额已更新' : '配额年度已新增', String(y));
                await CarbonAPI.reloadQuiet();
              } catch (err) { done(); toast('err', err.message); }
            };
          } });
      };
      root.querySelectorAll('[data-q-edit]').forEach(b => b.onclick = () => {
        const y = Number(b.dataset.qEdit);
        openForm(qRows().find(r => r.year === y));
      });
      root.querySelectorAll('[data-q-del]').forEach(b => b.onclick = async () => {
        const y = Number(b.dataset.qDel);
        const ok = await confirm({ title: '删除配额', content: `确定删除 ${y} 年度配额记录？`, okText: '删除', danger: true });
        if (!ok) return;
        const row = qRows().find(r => r.year === y);
        try {
          await CarbonAPI.request('DELETE', '/quota-years/' + row.dbId);
          sel.delete(String(y)); toast('ok', '已删除', y + ' 年度');
          await CarbonAPI.reloadQuiet();
        } catch (err) { toast('err', err.message); }
      });
      $('#q-new').onclick = () => openForm(null);
      $('#q-bulk').onclick = async () => {
        const n = sel.size;
        const ok = await confirm({ title: '批量删除配额', content: `将删除 ${n} 条记录，确认？`, okText: '删除', danger: true });
        if (!ok) return;
        try {
          for (const row of qRows().filter(r => sel.has(String(r.year)))) {
            await CarbonAPI.request('DELETE', '/quota-years/' + row.dbId);
          }
          sel.clear(); toast('ok', '已删除 ' + n + ' 条');
          await CarbonAPI.reloadQuiet();
        } catch (err) { toast('err', err.message); }
      };
    }
  });

  /* ============ 碳足迹核算 ============ */
  const fpState = { pid: null, kw: '', from: '', to: '', boundary: '', sel: new Set() };

  route('/carbon-footprint/product-footprint/list', {
    title: '碳足迹核算', group: '碳足迹管理',
    render() {
      const list = DB.footprint || [];
      const p = list.find(x => x.id === fpState.pid) || list[0];
      const kw = fpState.kw || '';
      const rows = list.filter(x => (!kw || (x.name + x.id).toLowerCase().includes(kw.toLowerCase())) && (!fpState.boundary || x.boundary === fpState.boundary) && (!fpState.from || String(x.date || '').slice(0, 7) >= fpState.from) && (!fpState.to || String(x.date || '').slice(0, 7) <= fpState.to));
      const sel = fpState.sel;
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">碳足迹核算</h1>
          <p class="page-desc">按功能单位核算产品生命周期碳足迹，支持阶段拆分与台账管理</p></div>
          <div class="page-actions">
            <button class="btn btn-primary" id="fp-new">${icon('plus', 15)} 新建</button></div></div>

        <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
          <div class="field"><label>产品名称</label><input class="input" id="fp-kw" placeholder="输入名称/编号" value="${esc(kw)}"></div>
          <div class="field"><label>核算边界</label>${UI.selectMenu({ key: 'fp-boundary', options: ['全部', '摇篮到大门', '摇篮到坟墓'] })}</div>
          <div class="field"><label>核算日期</label>
            <div class="pick" id="fp-mpick">
              <input class="input" id="fp-period" value="${esc(fpState.from ? fpState.from + ' ~ ' + (fpState.to || '') : '')}" placeholder="选择月份范围" readonly>
              <span class="pick-ico"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"><rect x="3" y="5" width="18" height="16" rx="3"/><path d="M8 3v4M16 3v4M3 10h18"/></svg></span>
              <div class="pick-pop floating" id="fp-mpop">
                <div class="pick-head"><button type="button" data-fmy="-1" aria-label="上一年">‹</button><b id="fp-my"></b><button type="button" data-fmy="1" aria-label="下一年">›</button></div>
                <div class="pick-grid months" id="fp-mgrid"></div>
              </div>
            </div></div>
          <div class="field"><label>&nbsp;</label><div class="toolbar">
            <button class="btn btn-primary" id="fp-search">查询</button><button class="btn" id="fp-reset">重置</button></div></div>
          <div class="field f-actions"><label>&nbsp;</label>
            <button class="btn btn-danger" id="fp-bulk" ${sel.size ? '' : 'disabled'}>批量删除${sel.size ? '（' + sel.size + '）' : ''}</button></div>
        </div></div></section>

        <div class="grid grid-main">
          <section class="card"><div class="card-head"><h2 class="card-title">产品清单</h2><span class="card-sub">共 ${rows.length} 个产品</span>
              <span class="small muted right">已选 ${sel.size} 项</span></div>
            <div class="table-wrap"><table class="tb"><thead><tr>
              <th style="width:34px"><input type="checkbox" data-fp-all></th>
              <th>产品名称</th><th>产品数量</th><th>产品单位</th><th>产品介绍</th><th>碳足迹值</th><th>碳足迹单位</th>
              <th>碳足迹日期</th><th>核算边界</th><th>备注</th><th>企业</th><th style="width:130px">操作</th></tr></thead>
              <tbody>${rows.map(x => `<tr class="${x.id === fpState.pid ? 'selected' : ''}" data-pick="${x.id}" style="cursor:pointer">
                <td><input type="checkbox" data-fp-check="${x.id}" ${sel.has(x.id) ? 'checked' : ''}></td>
                <td><b>${esc(x.name)}</b><div class="small muted">${x.id}</div></td>
                <td class="num">${int(x.qty)}</td><td>${x.unit}</td><td class="muted">—</td>
                <td class="num"><b>${x.footprint.toFixed(3)}</b></td><td>tCO₂e/${x.unit}</td>
                <td>${x.date}</td><td><span class="tag tag-info">${esc(x.boundary)}</span></td>
                <td>${x.remark ? esc(x.remark) : '<span class="muted">—</span>'}</td><td>${esc(DB.org.short)}</td>
                <td class="actions"><button class="btn btn-sm" data-fp-edit="${x.id}">编辑</button><button class="btn btn-sm btn-ghost" data-fp-del="${x.id}">删除</button></td></tr>`).join('')}
              </tbody></table></div>
          </section>

          <section class="card"><div class="card-head"><h2 class="card-title">阶段构成</h2><span class="card-sub">${p ? esc(p.name) : '未选择产品'}</span></div>
            <div class="card-body">${p ? `
              ${[['原材料获取', p.stage.raw, '#0f7a5a'], ['运输', p.stage.transport, '#12a374'], ['生产制造', p.stage.make, '#f59e0b']].map(([n, v, c]) => `<div style="margin-bottom:11px">
                <div class="between small"><span>${n}</span><b class="mono">${Number(v || 0).toFixed(3)} tCO₂e</b></div>
                <div class="progress" style="margin-top:4px"><i style="width:${p.footprint ? (v / p.footprint * 100).toFixed(1) : 0}%;background:${c}"></i></div></div>`).join('')}
              <div class="hr"></div>
              <div class="between"><span class="muted">合计（1 ${p.unit}）</span><b class="mono" style="font-size:16px">${Number(p.footprint || 0).toFixed(3)} tCO₂e</b></div>
              <div class="small muted" style="margin-top:6px">功能单位产品数量：${int(p.qty)} ${p.unit}</div>` : `<div class="tb-empty">${icon('chart')}<div>还没有产品足迹数据</div><div class="small">点右上角「新建」录入</div></div>`}</div></section>
        </div>
      </div>`;
    },
    mount(root) {
      const sel = fpState.sel;
      const syncBulk = () => {
        const b = $('#fp-bulk');
        if (b) { b.disabled = sel.size === 0; b.textContent = '批量删除' + (sel.size ? '（' + sel.size + '）' : ''); }
      };
      root.querySelectorAll('[data-pick]').forEach(tr => tr.onclick = (e) => {
        if (e.target.closest('button') || e.target.tagName === 'INPUT') return;
        fpState.pid = tr.dataset.pick; UI.render();
      });
      root.querySelectorAll('[data-fp-check]').forEach(c => c.onchange = () => {
        c.checked ? sel.add(c.dataset.fpCheck) : sel.delete(c.dataset.fpCheck);
        syncBulk();
      });
      const all = root.querySelector('[data-fp-all]');
      if (all) all.onchange = () => {
        root.querySelectorAll('[data-fp-check]').forEach(c => {
          c.checked = all.checked;
          all.checked ? sel.add(c.dataset.fpCheck) : sel.delete(c.dataset.fpCheck);
        });
        syncBulk();
      };
      syncBulk();

      const pInp = $('#fp-period'), pPop = $('#fp-mpop'), pGrid = $('#fp-mgrid'), pHead = $('#fp-my');
      if (pInp) {
        let ry = Number((fpState.to || fpState.from || thisMonth()).slice(0, 4));
        let from = fpState.from, to = fpState.to, first = true;
        const pad = n => String(n).padStart(2, '0');
        const paint = () => {
          pHead.textContent = ry + ' 年';
          pGrid.innerHTML = Array.from({ length: 12 }, (_, i) => {
            const m = pad(i + 1), v = ry + '-' + m;
            const cls = [from && to && v >= from && v <= to ? 'in' : '', v === from ? 'start' : '', v === to ? 'end' : ''].join(' ').trim();
            return `<button type="button" data-fm="${m}" class="${cls}">${i + 1}月</button>`;
          }).join('');
          pGrid.querySelectorAll('[data-fm]').forEach(b => b.onclick = (ev) => {
            ev.stopPropagation();
            const v = ry + '-' + b.dataset.fm;
            if (first) { from = v; to = ''; first = false; pInp.value = from + ' ~ '; pInp.dataset.from = from; delete pInp.dataset.to; }
            else {
              if (v < from) { to = from; from = v; } else to = v;
              first = true;
              pInp.value = from + ' ~ ' + to;
              pInp.dataset.from = from; pInp.dataset.to = to;
              pPop.classList.remove('open');
            }
            paint();
          });
        };
        pInp.onclick = () => { if (!pPop.classList.contains('open')) { first = true; paint(); pPop.classList.add('open'); } else pPop.classList.remove('open'); };
        root.querySelectorAll('[data-fmy]').forEach(b => b.onclick = (ev) => { ev.stopPropagation(); ry += Number(b.dataset.fmy); paint(); });
        root.addEventListener('click', e => { if (!e.target.closest('#fp-mpick')) pPop.classList.remove('open'); });
        UI.bindSelects(root);
      }
      $('#fp-search').onclick = () => {
        fpState.kw = $('#fp-kw').value.trim();
        const bsel = root.querySelector('[data-filter="fp-boundary"]');
        fpState.boundary = bsel ? bsel.value : '';
        const el = $('#fp-period');
        fpState.from = el && el.dataset.from ? el.dataset.from : '';
        fpState.to = el && el.dataset.to ? el.dataset.to : '';
        UI.render(); toast('ok', '查询完成');
      };
      $('#fp-reset').onclick = () => { fpState.kw = ''; fpState.from = ''; fpState.to = ''; fpState.boundary = ''; sel.clear(); UI.resetSelects($('#view')); UI.render(); };
      $('#fp-kw').onkeydown = e => { if (e.key === 'Enter') $('#fp-search').click(); };
      $('#fp-bulk').onclick = async () => {
        const n = sel.size;
        const ok = await confirm({ title: '提示', content: '确定要删除选中项吗？', okText: '删除', danger: true });
        if (!ok) return;
        try {
          const targets = DB.footprint.filter(x => sel.has(x.id));
          for (const x of targets) await CarbonAPI.request('DELETE', '/footprints/' + x.dbId);
          sel.clear(); toast('ok', '已删除 ' + n + ' 条');
          await CarbonAPI.reloadQuiet();
        } catch (err) { toast('err', err.message); }
      };
      root.querySelectorAll('[data-fp-del]').forEach(b => b.onclick = async (e) => {
        e.stopPropagation();
        const x = DB.footprint.find(v => v.id === b.dataset.fpDel);
        const ok = await confirm({ title: '提示', content: '确定要删除选中项吗？', okText: '删除', danger: true });
        if (!ok) return;
        try {
          await CarbonAPI.request('DELETE', '/footprints/' + x.dbId);
          sel.delete(x.id); toast('ok', '已删除', x.name);
          await CarbonAPI.reloadQuiet();
        } catch (err) { toast('err', err.message); }
      });
      root.querySelectorAll('[data-fp-edit]').forEach(b => b.onclick = (e) => {
        e.stopPropagation();
        const x = DB.footprint.find(v => v.id === b.dataset.fpEdit);
        const y0 = String(x.date || (thisYear() + '-01-01')).slice(0, 4);
        modal({ title: '编辑', width: 680,
          body: `<form id="fpe-form">
            <div class="grid grid-2">
              <div class="field"><label>年度</label><select class="select" name="year">${yearOptions().map(y => `<option ${y === y0 ? 'selected' : ''}>${y}</option>`).join('')}</select></div>
              <div class="field"><label>产品</label><select class="select" name="name">${DB.products.map(pp => `<option ${pp.name === x.name ? 'selected' : ''}>${esc(pp.name)}</option>`).join('')}</select></div>
            </div>
            <div class="grid grid-2" style="margin-top:10px">
              <div class="field"><label>产品单位</label><input class="input" name="unit" value="${esc(x.unit)}" readonly></div>
              <div class="field"><label>产品产量</label><input class="input" name="qty" type="number" value="${x.qty}"></div>
            </div>
            <div class="grid grid-2" style="margin-top:10px">
              <div class="field"><label>碳足迹值</label><input class="input" name="footprint" value="${x.footprint}" readonly></div>
              <div class="field"><label>碳足迹单位</label><select class="select" name="fpUnit">${['tCO₂e/t', 'kgCO₂e/kg', 'gCO₂e/g', 'tCO₂e/万Nm³', 'tCO₂e/Nm³', 'tCO₂e/万个', 'kgCO₂e/个', 'tCO₂e/万件', 'kgCO₂e/件', 'tCO₂e/万m'].map(u => `<option ${u === (x.fpUnit || 'tCO₂e/' + x.unit) ? 'selected' : ''}>${u}</option>`).join('')}</select></div>
            </div>
            <div class="grid grid-2" style="margin-top:10px">
              <div class="field"><label>核算边界</label><select class="select" name="boundary">${['摇篮到大门', '摇篮到坟墓'].map(v => `<option ${v === (x.boundary || '摇篮到大门') ? 'selected' : ''}>${v}</option>`).join('')}</select></div>
              <div class="field"><label>备注</label><input class="input" name="remark" value="${esc(x.remark || '')}" placeholder="选填"></div>
            </div></form>`,
          foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="fpe-save">保存</button>`,
          onMount(w, c) {
            w.querySelector('#fpe-save').onclick = async (e) => {
              const d = Object.fromEntries(new FormData(w.querySelector('#fpe-form')));
              if (!d.qty || Number(d.qty) <= 0) { toast('err', '参数验证错误，请仔细填写表单数据!'); return; }
              const done = busy(e.currentTarget, '保存中');
              const mmdd = String(x.date || '').slice(4, 10) || '-01-01';
              const date = (d.year || y0) + mmdd;
              try {
                const st = x.stage || {};
                await CarbonAPI.request('PUT', '/footprints/' + x.dbId, {
                  productId: x.productId || null, name: d.name, unit: d.unit || x.unit,
                  qty: Number(d.qty), footprint: Number(d.footprint || x.footprint || 0) || 0.0001,
                  date, boundary: d.boundary || x.boundary || '摇篮到大门',
                  remark: d.remark || '',
                  stageRaw: st.raw == null ? null : Number(st.raw),
                  stageTransport: st.transport == null ? null : Number(st.transport),
                  stageMake: st.make == null ? null : Number(st.make),
                  version: x.version
                });
                done(); c(); toast('ok', '保存成功', d.name);
                await CarbonAPI.reloadQuiet();
              } catch (err) { done(); toast('err', err.message); }
            };
          } });
      });
      $('#fp-new').onclick = () => {
        const defYear = String(thisYear());
        modal({ title: '添加', width: 680,
          body: `<form id="fp-form">
            <div class="grid grid-2">
              <div class="field" data-f="year"><label class="req">年度</label>
                <select class="select" name="year">${yearOptions().map(y => `<option>${y}</option>`).join('')}</select><div class="err-msg"></div></div>
              <div class="field" data-f="name"><label class="req">产品</label>
                <select class="select" name="name">${DB.products.map(p => `<option>${esc(p.name)}</option>`).join('')}</select><div class="err-msg"></div></div>
            </div>
            <div class="grid grid-2" style="margin-top:10px">
              <div class="field" data-f="unit"><label class="req">产品单位</label><input class="input" name="unit" placeholder="如 吨"><div class="err-msg"></div></div>
              <div class="field" data-f="qty"><label class="req">产品产量</label><input class="input" name="qty" type="number" placeholder="如 5200"><div class="err-msg"></div></div>
            </div>
            <div class="grid grid-2" style="margin-top:10px">
              <div class="field" data-f="footprint"><label class="req">碳足迹值</label><input class="input" name="footprint" type="number" step="0.001" placeholder="如 1.842"><div class="err-msg"></div></div>
              <div class="field" data-f="fpUnit"><label class="req">碳足迹单位</label>
                <select class="select" name="fpUnit"><option>tCO₂e/t</option><option>kgCO₂e/kg</option><option>gCO₂e/g</option><option>tCO₂e/万Nm³</option><option>tCO₂e/Nm³</option><option>tCO₂e/万个</option><option>kgCO₂e/个</option><option>tCO₂e/万件</option><option>kgCO₂e/件</option><option>tCO₂e/万m</option></select><div class="err-msg"></div></div>
            </div>
            <div class="grid grid-2" style="margin-top:10px">
              <div class="field" data-f="boundary"><label class="req">核算边界</label>
                <select class="select" name="boundary"><option>摇篮到大门</option><option>摇篮到坟墓</option></select><div class="err-msg"></div></div>
              <div class="field"><label>备注</label><input class="input" name="remark" placeholder="选填"></div>
            </div></form>`,
          foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="fp-save">保存</button>`,
          onMount(wrap, close) {
            const form = wrap.querySelector('#fp-form');
            const setE = (n, m) => { const f = form.querySelector(`[data-f="${n}"]`); if (!f) return; f.classList.toggle('error', !!m); f.querySelector('.err-msg').textContent = m || ''; };
            form.querySelectorAll('input,select').forEach(i => i.oninput = () => setE(i.name, ''));
            wrap.querySelector('#fp-save').onclick = async (e) => {
              const d = Object.fromEntries(new FormData(form));
              let bad = false;
              if (!d.name) { setE('name', '产品 必填'); bad = true; }
              if (!d.unit || !d.unit.trim()) { setE('unit', '产品单位 必填'); bad = true; }
              if (!d.qty || Number(d.qty) <= 0) { setE('qty', '产品产量 必填'); bad = true; }
              if (!d.footprint || Number(d.footprint) <= 0) { setE('footprint', '碳足迹值 必填'); bad = true; }
              if (bad) { toast('err', '参数验证错误，请仔细填写表单数据!'); return; }
              const done = busy(e.currentTarget, '保存中');
              const fp = Number(d.footprint);
              try {
                const prod = (DB.products || []).find(x => x.name === d.name);
                await CarbonAPI.request('POST', '/footprints', {
                  productId: prod ? prod.dbId : null, name: d.name, unit: d.unit,
                  qty: Number(d.qty), footprint: fp, date: d.year + '-01-01',
                  boundary: d.boundary,
                  remark: d.remark || '',
                  stageRaw: Math.round(fp * 0.61 * 1000) / 1000,
                  stageTransport: Math.round(fp * 0.11 * 1000) / 1000,
                  stageMake: Math.round(fp * 0.28 * 1000) / 1000
                });
                done(); close(); toast('ok', '保存成功', d.name);
                await CarbonAPI.reloadQuiet();
              } catch (err) { done(); toast('err', err.message); }
            };
          } });
      };
    }
  });

  /* ============ 报告三页（共用生成流程） ============ */
  const REPORT_DEF = {
    emission: {
      path: '/carbon-report/calculation-report/query', title: '碳排放报告', group: '报告查询',
      desc: '按期间汇总范围一/二排放，含活动数据与因子明细',
    },
    energy: {
      path: '/carbon-report/energy-utilization-report/query', title: '能源利用报告', group: '报告查询',
      desc: '能源消费结构、折标煤与能效指标，适用节能审查',
    },
    footprint: {
      path: '/carbon-report/product-footprint-report/query', title: '产品碳足迹报告', group: '报告查询',
      desc: '按功能单位输出生命周期各阶段排放，适用客户披露',
    },
  };

  function reportPeriod() {
    const rp = document.querySelector('#view [data-rp]');
    const to = rp && rp.dataset.to ? rp.dataset.to : localDate();
    return to.slice(0, 7);
  }

  function reportFootprint() {
    const sel = document.querySelector('#view #r-product');
    const name = sel ? sel.value : '';
    if (!name || name === '全部') return null;
    const list = DB.footprint || [];
    return list.find(f => f.name === name) || null;
  }

  const RPT_PALETTE = ['#0f7a5a', '#f59e0b', '#64748b', '#94a3b8', '#cbd5e1'];

  function liveKpis(v) {
    const k = v.kpis || [];
    if (!k.length) return '';
    return `<div class="grid grid-${Math.min(4, k.length)}" style="margin-bottom:13px">${k.map(x =>
      `<div class="kpi"><div class="kpi-label">${esc(x.label)}</div>
        <div class="kpi-value">${esc(String(x.value))}<small>${esc(x.unit || '')}</small></div>
        <div class="kpi-foot">${esc(x.foot || '')}</div></div>`).join('')}</div>`;
  }

  function liveTable(v) {
    if (!v.table) return '';
    const cols = v.table.columns || [], rows = v.table.rows || [];
    return `<div class="card-head"><h2 class="card-title">${esc(v.table.title || '明细')}</h2>
        <span class="card-sub">${rows.length} 行</span></div>
      <div class="table-wrap"><table class="tb"><thead><tr>${cols.map(c =>
        `<th class="${c.type === 'number' ? 'num' : ''}">${esc(c.label)}</th>`).join('')}</tr></thead>
        <tbody>${rows.length ? rows.map(r => `<tr>${cols.map(c =>
          `<td class="${c.type === 'number' ? 'num' : ''}">${esc(r[c.key] == null ? '—' : String(r[c.key]))}</td>`).join('')}</tr>`).join('')
          : `<tr><td colspan="${cols.length || 1}"><div class="tb-empty">该区间没有数据</div></td></tr>`}</tbody></table></div>`;
  }

  function liveCharts(v) {
    const list = (v.series || []).filter(x => (x.values || []).length);
    const cols = (v.table && v.table.columns) || [], rows = (v.table && v.table.rows) || [];
    const nameKey = (cols[0] || {}).key;
    const prefer = ['tce', 'emission', 'value', 'amount', 'qty'];
    const nums = cols.filter(c => c.type === 'number' && c.key !== 'share');
    const numCol = prefer.map(k => nums.find(c => c.key === k)).find(Boolean) || nums[0];
    const bars = numCol ? rows.slice(0, 8).map(r => ({ name: String(r[nameKey] == null ? '' : r[nameKey]), value: Number(r[numCol.key]) || 0, color: '#0f7a5a' })) : [];
    let html = liveKpis(v);
    if (list.length) {
      const labels = list[0].labels || [];
      html += `<div class="grid grid-2" style="margin-bottom:13px">
        <section class="card"><div class="card-head"><h2 class="card-title">月度趋势</h2><span class="card-sub">${esc(list[0].unit || '')}</span></div>
          <div class="card-body">${multiLine(labels, list.slice(0, 4).map((x, i) => ({ name: x.name, color: RPT_PALETTE[i % 5], values: x.values })))}</div></section>
        ${bars.length ? `<section class="card"><div class="card-head"><h2 class="card-title">构成</h2><span class="card-sub">${esc(numCol.label)}</span></div>
          <div class="card-body">${hbar(bars, '')}</div></section>` : ''}
      </div>`;
    }
    return html;
  }

  function generateFlow(kind) {
    const label = REPORT_DEF[kind].title;
    const steps = ['校验数据完整性', '渲染图表与明细', '合成 PDF 文件'];
    let closed = false;
    const m = modal({
      title: '生成 · ' + label, width: 520,
      body: `<div class="between" style="margin-bottom:9px"><span class="small muted" id="gs">准备中…</span><b class="mono" id="gp">0%</b></div>
        <div class="progress"><i id="gb" style="width:0%"></i></div>
        <div class="stack" style="margin-top:14px" id="gsteps">${steps.map((s, i) => `<div class="row small" data-step="${i}" style="color:var(--ink-400)">
          <span class="spin" style="display:none;width:12px;height:12px;border:2px solid var(--brand-500);border-right-color:transparent;border-radius:50%"></span>
          <span class="dotmark" style="width:16px;height:16px;border-radius:50%;border:1.5px solid var(--line-strong);display:grid;place-items:center;font-size:10px">${i + 1}</span>${s}</div>`).join('')}</div>
        <div id="gres" style="margin-top:14px"></div>`,
      foot: `<button class="btn" data-close id="gclose">取消</button>`,
      onClose() { closed = true; }
    });
    const bar = m.el.querySelector('#gb'), pct = m.el.querySelector('#gp'), stepEl = m.el.querySelector('#gs');
    const host = m.el.querySelector('#gsteps'), res = m.el.querySelector('#gres'), closeBtn = m.el.querySelector('#gclose');
    const setStep = (i, st) => {
      const row = host.querySelector(`[data-step="${i}"]`); if (!row) return;
      const spin = row.querySelector('.spin'), mark = row.querySelector('.dotmark');
      if (st === 'run') { spin.style.display = 'inline-block'; mark.style.display = 'none'; row.style.color = 'var(--ink-700)'; }
      if (st === 'ok') { spin.style.display = 'none'; mark.style.display = 'grid'; mark.textContent = '✓'; mark.style.borderColor = 'var(--ok-600)'; mark.style.color = 'var(--ok-600)'; row.style.color = 'var(--ink-700)'; }
      if (st === 'fail') { spin.style.display = 'none'; mark.style.display = 'grid'; mark.textContent = '!'; mark.style.borderColor = 'var(--danger-600)'; mark.style.color = 'var(--danger-600)'; row.style.color = 'var(--danger-600)'; }
    };
    const finish = (meta) => {
      bar.style.width = '100%'; pct.textContent = '100%'; stepEl.textContent = '已完成';
      const sha = meta.sha256 ? `　指纹 ${String(meta.sha256).slice(0, 12)}…` : '';
      res.innerHTML = `<div style="background:var(--ok-100);border:1px solid #bfe6cb;border-radius:10px;padding:12px">
        <div style="font-weight:650;color:var(--ok-600)">报告已生成</div>
        <div class="small">${esc(meta.fileName || label)}${esc(sha)}</div>
        <div class="row" style="margin-top:9px"><button class="btn btn-sm btn-primary" id="gopen">下载 PDF</button></div></div>`;
      res.querySelector('#gopen').onclick = async (e) => {
        if (!meta.id) { toast('warn', '报告文件尚未就绪，请稍后重试'); return; }
        const b = busy(e.currentTarget, '下载中');
        try { await CarbonAPI.download('/reports/' + meta.id + '/download'); b(); }
        catch (err) { b(); toast('err', err.message); }
      };
      closeBtn.textContent = '完成';
      toast('ok', '报告生成成功', meta.fileName || label);
      setTimeout(() => CarbonAPI.reloadQuiet(), 300);
    };
    (async () => {
        setStep(0, 'run'); stepEl.textContent = steps[0]; await sleep(180); setStep(0, 'ok');
        setStep(1, 'run'); stepEl.textContent = steps[1];
        bar.style.width = '55%'; pct.textContent = '55%';
        let meta;
        try {
          const fp = kind === 'footprint' ? reportFootprint() : null;
          if (kind === 'footprint' && !fp) throw new Error((DB.footprint || []).length ? '请先选择要生成报告的产品' : '还没有产品足迹记录，无法生成');
          meta = await CarbonAPI.request('POST', '/reports', {
            type: kind === 'emission' ? 'carbon' : kind,
            period: kind === 'footprint' ? null : reportPeriod(),
            footprintId: fp ? fp.dbId : null
          });
        } catch (err) {
          setStep(1, 'fail'); stepEl.textContent = '生成失败';
          res.innerHTML = `<div style="background:var(--danger-100);border:1px solid #f3c7c7;border-radius:10px;padding:12px">
            <div style="font-weight:650;color:var(--danger-600)">${esc(err.message)}</div>
            <div class="small" style="color:var(--ink-700)">请确认该期间已完成核算，或换一个期间重试。</div></div>`;
          closeBtn.textContent = '关闭';
          return;
        }
        if (closed) return;
        setStep(1, 'ok'); setStep(2, 'run'); stepEl.textContent = steps[2];
        bar.style.width = '86%'; pct.textContent = '86%'; await sleep(280);
        if (closed) return;
        setStep(2, 'ok');
        finish(meta || {});
    })();
  }

  /* ---- 报告页图表助手 ---- */
  function donutC(items, centerTop, centerMain, size = 176) {
    const total = items.reduce((s, i) => s + i.value, 0) || 1;
    const r = 64, c = 2 * Math.PI * r;
    let acc = 0;
    const arcs = items.filter(it => it.value > 0).map(it => {
      const len = (it.value / total) * c;
      if (len < 3.2) { acc += len; return ''; }
      const seg = `<circle cx="88" cy="88" r="${r}" fill="none" stroke="${it.color}" stroke-width="18"
        stroke-dasharray="${len - 2.5} ${c - len + 2.5}" stroke-dashoffset="${-acc}" stroke-linecap="butt"><title>${it.name} ${it.value}%</title></circle>`;
      acc += len; return seg;
    }).join('');
    return `<svg viewBox="0 0 176 176" width="${size}" height="${size}" role="img" aria-label="构成">
      <circle cx="88" cy="88" r="${r}" fill="none" stroke="#f1f5f9" stroke-width="18"/>
      <g transform="rotate(-90 88 88)">${arcs}</g>
      <text x="88" y="76" text-anchor="middle" font-size="10.5" fill="#64748b">${esc(centerTop)}</text>
      <text x="88" y="96" text-anchor="middle" font-size="17" font-weight="700" fill="#0f172a">${esc(centerMain.split(' ')[0])}</text>
      <text x="88" y="112" text-anchor="middle" font-size="10" fill="#94a3b8">${esc(centerMain.split(' ').slice(1).join(' '))}</text></svg>`;
  }

  function hbar(rows, unit = '') {
    const w = 660, rowH = 30, pad = { l: 104, r: 130, t: 6, b: 18 };
    const h = pad.t + pad.b + rows.length * rowH;
    const max = Math.max(...rows.map(r => Math.abs(r.value))) || 1;
    return `<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="横向条形图">${rows.map((r, i) => {
      const y = pad.t + i * rowH + 5;
      const bw = Math.max(2, (Math.abs(r.value) / max) * (w - pad.l - pad.r));
      return `<g><text x="${pad.l - 10}" y="${y + 13}" text-anchor="end" font-size="10.5" fill="#64748b">${esc(r.name)}</text>
        <rect x="${pad.l}" y="${y}" width="${w - pad.l - pad.r}" height="16" rx="3" fill="#f1f5f9"/>
        <rect x="${pad.l}" y="${y}" width="${bw}" height="16" rx="3" fill="${r.color || '#0f7a5a'}"/>
        <text x="${pad.l + bw + 7}" y="${y + 12}" font-size="10.5" font-weight="600" fill="#0f172a">${num(r.value, 2)} ${esc(unit)}</text></g>`;
    }).join('')}</svg>`;
  }

  function multiLine(labels, series) {
    const w = 700, h = 200, pad = { l: 46, r: 14, t: 10, b: 24 };
    const max = niceMax(Math.max(...series.flatMap(s => s.values), 1) * 1.06, 3);
    const x = i => pad.l + (i * (w - pad.l - pad.r)) / Math.max(1, labels.length - 1);
    const y = v => pad.t + (h - pad.t - pad.b) * (1 - v / max);
    const grid = [0, 1, 2, 3].map(k => {
      const yy = pad.t + ((h - pad.t - pad.b) * k) / 3;
      const v = max * (1 - k / 3);
      return `<line x1="${pad.l}" y1="${yy}" x2="${w - pad.r}" y2="${yy}" stroke="#eef2f6"/>
        <text x="${pad.l - 7}" y="${yy + 4}" text-anchor="end" font-size="9.5" fill="#94a3b8">${v === 0 ? '0' : max >= 1000 ? (v / 1000).toFixed(1).replace(/\.0$/, '') + 'k' : v.toFixed(1)}</text>`;
    }).join('');
    const paths = series.map(s => `<path d="${s.values.map((v, i) => `${i ? 'L' : 'M'}${x(i)},${y(v)}`).join(' ')}" fill="none" stroke="${s.color}" stroke-width="2"/>`).join('');
    const dots = series.map(s => s.values.map((v, i) => `<circle cx="${x(i)}" cy="${y(v)}" r="2.2" fill="${s.color}"/>`).join('')).join('');
    const xl = labels.map((l, i) => `<text x="${x(i)}" y="${h - 6}" text-anchor="middle" font-size="9.5" fill="#94a3b8">${esc(l)}</text>`).join('');
    const legend = `<div class="row small" style="gap:14px;margin-bottom:4px">${series.map(s => `<span><i style="display:inline-block;width:12px;height:3px;border-radius:2px;background:${s.color};margin-right:5px;vertical-align:middle"></i>${esc(s.name)}</span>`).join('')}</div>`;
    return `${legend}<svg class="chart" viewBox="0 0 ${w} ${h}" role="img" aria-label="折线图">${grid}${paths}${dots}${xl}</svg>`;
  }

  function treeTable5(cols, rows) {
    return `<div class="table-wrap"><table class="tb tree"><thead><tr>${cols.map(c => `<th class="${c.num ? 'num' : ''}">${esc(c.t)}</th>`).join('')}</tr></thead>
      <tbody>${rows.map(r => r.sum
        ? `<tr class="sum"><td><b>${esc(r.name)}</b></td><td colspan="${cols.length - 1}" class="num"><b>${esc(r.value)}</b></td></tr>`
        : `<tr><td><span style="display:inline-block;padding-left:${8 + (r.depth || 0) * 18}px">${r.children ? '<span class="caret" style="color:#94a3b8;margin-right:4px">▾</span>' : ''}${r.bold ? `<b>${esc(r.name)}</b>` : esc(r.name)}</span></td>
          ${cols.slice(1).map(c => `<td class="${c.num ? 'num' : ''}">${esc(r[c.k] ?? '—')}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
  }


  const RPT_FILTERS = (kind) => `<div class="filters rpt-filters">
    ${kind === 'footprint'
      ? `<div class="field" style="min-width:240px"><label>日期</label>${UI.selectMenu({ id: 'r-year', options: yearOptions() })}</div>`
      : `<div class="field" style="min-width:240px"><label>日期范围</label>${UI.rangePicker({ mode: 'date', from: thisYear() + '-01-01', to: thisMonth() + '-01', ph: '开始日期 ~ 结束日期' })}</div>`}
    ${kind === 'footprint'
      ? `<div class="field" style="min-width:200px"><label>产品名称</label>${UI.selectMenu({ id: 'r-product', options: ['全部', ...(DB.footprint || []).map(f => f.name)] })}</div>`
      : ''}
    <div class="field"><label>&nbsp;</label><div class="toolbar">
      <button class="btn btn-primary" id="r-query">查询</button>
      <button class="btn" id="r-reset">重置</button>
      <button class="btn btn-brand" id="r-gen">${icon('file', 15)} 生成报告</button>
    </div></div></div>`;


  /* 核算日期统计页签：趋势图 + 分析表（竞品独立一套图表） */
  function emissionByMonth() {
    const ms = DB.monthly.slice(9, 11);
    const out = ms.map(m => Math.round(m.energy * 12.5 + 260));
    return `<div class="grid grid-2" style="margin-bottom:13px">
      <section class="card"><div class="card-head"><h2 class="card-title">能耗趋势图 (tce)</h2></div>
        <div class="card-body">${multiLine(ms.map(m => m.m), [
          { name: '化石燃料', color: '#0f7a5a', values: ms.map(m => Math.round(m.energy * 0.12)) },
          { name: '电力', color: '#f59e0b', values: ms.map(m => Math.round(m.energy * 0.75)) },
          { name: '热力', color: '#64748b', values: ms.map(m => Math.round(m.energy * 0.13)) }])}</div></section>
      <section class="card"><div class="card-head"><h2 class="card-title">CO₂及产值趋势图</h2></div>
        <div class="card-body">${multiLine(ms.map(m => m.m), [
          { name: '产值', color: '#0f7a5a', values: out },
          { name: '排放量', color: '#f59e0b', values: ms.map(m => m.emission) }])}</div></section></div>
      <section class="card"><div class="card-head"><h2 class="card-title">能碳数据分析表</h2>
        <span class="card-sub">碳排放强度(tCO₂e/万元) <b class="mono">0.25</b>　能耗强度(tce/万元) <b class="mono">0.08</b></span></div>
        <div class="table-wrap"><table class="tb"><thead><tr><th>日期</th><th class="num">能量消耗量（tce）</th><th class="num">碳排放量（tCO₂e）</th><th class="num">产值（万元）</th></tr></thead>
        <tbody>${ms.map((m, i) => `<tr><td>${esc(m.m || '')}</td><td class="num">${num(m.energy, 1)}</td><td class="num">${num(m.emission, 1)}</td><td class="num">${num(out[i], 0)}</td></tr>`).join('')}</tbody></table></div></section>`;
  }

  async function loadReportLive(kind) {
    const ep = kind === 'energy' ? '/analysis/energy-consumption'
      : kind === 'footprint' ? '/analysis/footprint' : '/analysis/carbon-emission';
    let q = '';
    if (kind === 'footprint') {
      const y = document.querySelector('#view #r-year');
      const p = document.querySelector('#view #r-product');
      const qs = [];
      if (y && y.value) qs.push('year=' + encodeURIComponent(y.value));
      if (p && p.value) qs.push('product=' + encodeURIComponent(p.value));
      q = qs.length ? '?' + qs.join('&') : '';
    } else {
      const rp = document.querySelector('#view [data-rp]');
      if (rp && rp.dataset.from && rp.dataset.to) q = '?from=' + rp.dataset.from.slice(0, 7) + '&to=' + rp.dataset.to.slice(0, 7);
    }
    let v;
    try { v = await CarbonAPI.request('GET', ep + q); } catch (e) { return; }
    const charts = document.querySelector('#r-charts');
    const main = document.querySelector('#r-main');
    if (charts) charts.innerHTML = liveCharts(v);
    if (main) main.innerHTML = liveTable(v);
  }

  Object.values(REPORT_DEF).forEach(def => {
    route(def.path, {
      title: def.title, group: def.group,
      render() {
        const kind = Object.keys(REPORT_DEF).find(k => REPORT_DEF[k].path === def.path);
        const hist = DB.reports.filter(r => r.kind === kind);
        const tabs = kind === 'emission' ? `<div class="tabs" id="r-tabs" style="margin:0 0 12px">
          <button data-rtab="0" class="active">核算数据统计</button><button data-rtab="1">核算日期统计</button></div>` : '';
        const chartBlock = `<div class="grid grid-2" style="margin-bottom:13px">
          <section class="card"><div class="card-head"><h2 class="card-title">趋势分析</h2></div>
            <div class="card-body"><div class="tb-empty">数据加载中…</div></div></section>
          <section class="card"><div class="card-head"><h2 class="card-title">构成分析</h2></div>
            <div class="card-body"><div class="tb-empty">数据加载中…</div></div></section>
        </div>`;

        const mainTable = `<section class="card"><div id="r-main"><div class="card-body"><div class="tb-empty">数据加载中…</div></div></div></section>`;

        const stageTables = '';

        const canDelete = (CarbonAPI.me() && CarbonAPI.me().role === 'admin');
        const histCard = `<section class="card" style="margin-top:13px"><div class="card-head">
            <h2 class="card-title">报告历史</h2><span class="card-sub">${hist.length} 份</span>
            <button class="btn btn-sm right" id="r-bundle">${icon('download', 14)} 打包 ZIP</button></div>
          ${hist.length ? `<div class="table-wrap"><table class="tb"><thead><tr>
              <th>报告名称</th><th>期间</th><th>大小</th><th>生成时间</th><th style="width:150px">操作</th></tr></thead>
            <tbody>${hist.map(r => `<tr><td>${esc(r.name)}</td><td>${esc(r.period || '—')}</td>
              <td>${esc(r.size || '—')}</td><td>${esc(r.at || '—')}</td>
              <td class="actions"><button class="btn btn-sm btn-ghost" data-dl="${r.dbId || ''}">下载</button>
              ${canDelete ? `<button class="btn btn-sm" data-del-rpt="${r.dbId || ''}">删除</button>` : ''}</td></tr>`).join('')}</tbody></table></div>`
            : `<div class="card-body"><div class="tb-empty">${icon('file')}<div>还没有生成过报告</div><div class="small">点右上角「生成报告」按当前期间合成 PDF</div></div></div>`}</section>`;

        return `<div class="page">
          <div class="page-head"><div><h1 class="page-title">${def.title}</h1><p class="page-desc">${def.desc}</p></div></div>
          <section class="card" style="margin-bottom:13px"><div class="card-body">${RPT_FILTERS(kind)}</div></section>
          ${tabs}
          <div id="r-charts">${chartBlock}</div>
          ${mainTable}
          ${stageTables}
          ${histCard}
        </div>`;
      },
      mount(root) {
        UI.bindRangePickers(root);
        UI.bindSelects(root);
        const kind = Object.keys(REPORT_DEF).find(k => REPORT_DEF[k].path === def.path);
        $('#r-gen').onclick = () => generateFlow(kind);
        $('#r-query').onclick = async (e) => {
          const done = busy(e.currentTarget, '查询中');
          await loadReportLive(kind); done(); toast('ok', '查询完成');
        };
        $('#r-reset').onclick = () => { toast('ok', '已重置条件'); UI.render(); };
        const bundleBtn = $('#r-bundle');
        if (bundleBtn) bundleBtn.onclick = async (e) => {
          const done = busy(e.currentTarget, '打包中');
          try {
            const type = kind === 'emission' ? 'carbon' : kind;
            await CarbonAPI.download('/reports/bundle?type=' + type + '&period=' + reportPeriod());
            done();
          } catch (err) { done(); toast('err', err.message); }
        };
        loadReportLive(kind);
        const tb = $('#r-tabs');
        if (tb) {
          const charts = $('#r-charts'), main = $('#r-main');
          const savedCharts = charts ? charts.innerHTML : '', savedMain = main ? main.innerHTML : '';
          tb.querySelectorAll('[data-rtab]').forEach(b => b.onclick = () => {
            tb.querySelectorAll('[data-rtab]').forEach(x => x.classList.toggle('active', x === b));
            if (!charts || !main) return;
            if (b.dataset.rtab === '0') { charts.style.display = ''; charts.innerHTML = savedCharts; main.innerHTML = savedMain; }
            else { charts.style.display = 'none'; main.innerHTML = emissionByMonth(); }
            toast('ok', '已切换至' + b.textContent.trim());
          });
        }
        root.querySelectorAll('[data-dl]').forEach(b => b.onclick = async (e) => {
          if (!b.dataset.dl) { toast('warn', '该报告缺少文件标识，无法下载'); return; }
          const done = busy(e.currentTarget, '下载中');
          try { await CarbonAPI.download('/reports/' + b.dataset.dl + '/download'); done(); }
          catch (err) { done(); toast('err', err.message); }
        });
        root.querySelectorAll('[data-del-rpt]').forEach(b => b.onclick = async () => {
          if (!b.dataset.delRpt) return;
          const ok = await confirm({ title: '删除报告', content: '删除后 PDF 不可恢复，确认删除？', okText: '删除', danger: true });
          if (!ok) return;
          try { await CarbonAPI.request('DELETE', '/reports/' + b.dataset.delRpt); toast('ok', '已删除'); await CarbonAPI.reloadQuiet(); }
          catch (err) { toast('err', err.message); }
        });
        root.querySelectorAll('[data-retry]').forEach(b => b.onclick = () => { state.demoReportFail = false; generateFlow(kind); });
      }
    });
  });

  /* ============ 设备管理（树 + 台账） ============ */
  const devState = { node: 'F1', kw: '', sel: new Set() };
  const flatten = () => (DB.devices || []).map(d => ({
    id: d.dbId, dbId: d.dbId, name: d.name, code: d.code,
    type: d.deviceType || '—', level: d.level || '—', energy: d.energyType,
    unit: d.unit, status: d.status || 'online', disabled: !!d.disabled,
    facility: d.facilityName || '—', facilityId: d.facilityId,
    remark: d.remark, version: d.version
  }));
  const findNode = (id) => (DB.facilities || []).find(n => n.id === id) || null;
  const nodeDevices = () => {
    const all = flatten();
    if (devState.node === 'all') return all;
    const names = new Set();
    const walk = (n) => { if (!n) return; names.add(n.name); (n.children || []).forEach(walk); };
    walk(findNode(devState.node));
    return names.size ? all.filter(d => names.has(d.facility)) : all;
  };

  const flatFacilities = () => {
    const out = [];
    const walk = (nodes) => (nodes || []).forEach(n => {
      out.push({ dbId: n.dbId, name: n.name, code: n.code });
      if (n.children) walk(n.children);
    });
    walk(DB.facilities);
    return out;
  };

  function deviceForm(d) {
    const editing = !!d;
    const facs = flatFacilities();
    const cur = editing ? d : { facility: facs[0] ? facs[0].name : '', level: '主要用能设备', type: '电表', energy: '外购电力', status: 'online' };
    modal({
      title: editing ? '编辑设备' : '添加设备', width: 760,
      body: `<form id="de-form">
        <div class="field"><label>所属设施</label><select class="select" name="facility">${facs.map(f =>
          `<option ${f.name === cur.facility ? 'selected' : ''}>${esc(f.name)}</option>`).join('')}</select></div>
        <div class="grid grid-2" style="margin-top:10px">
          <div class="field" data-f="name"><label class="req">设备名称</label><input class="input" name="name" value="${esc(cur.name || '')}" placeholder="如 空压机 A3"><div class="err-msg"></div></div>
          <div class="field" data-f="code"><label class="req">设备编码</label><input class="input" name="code" value="${esc(cur.code || '')}" placeholder="EQ-A103"><div class="err-msg"></div></div>
        </div>
        <div class="grid grid-2" style="margin-top:10px">
          <div class="field"><label>设备类型</label><select class="select" name="type">${['电表', '水表', '蒸汽表', '燃气表', '油表', '电子秤', '其他'].map(t =>
            `<option ${t === cur.type ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
          <div class="field"><label>计量级别</label><select class="select" name="level">${['进出用能单位', '次级用能单位', '主要用能设备'].map(l =>
            `<option ${l === cur.level ? 'selected' : ''}>${l}</option>`).join('')}</select></div>
        </div>
        <div class="grid grid-2" style="margin-top:10px">
          <div class="field"><label class="req">能源种类</label><select class="select" name="energy">${['外购电力', '天然气', '外购蒸汽', '柴油', '汽油', '其他'].map(en =>
            `<option ${en === cur.energy ? 'selected' : ''}>${en}</option>`).join('')}</select></div>
          <div class="field"><label>状态</label><select class="select" name="status">${[['online', '在线'], ['offline', '离线']].map(([v, t]) =>
            `<option value="${v}" ${v === cur.status ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
        </div>
        <div class="field" style="margin-top:10px"><label>备注</label><input class="input" name="remark" value="${esc(cur.remark || '')}" placeholder="选填"></div>
      </form>`,
      foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="de-save">保存</button>`,
      onMount(w, close) {
        const form = w.querySelector('#de-form');
        w.querySelector('#de-save').onclick = async (e) => {
          const dd = Object.fromEntries(new FormData(form));
          if (!dd.name || !dd.name.trim()) { toast('err', '设备名称必填'); return; }
          if (!dd.code || !dd.code.trim()) { toast('err', '设备编码必填'); return; }
          const fac = facs.find(f => f.name === dd.facility);
          const body = {
            name: dd.name.trim(), code: dd.code.trim(), energyType: dd.energy,
            unit: (editing && d.unit) || null, status: dd.status,
            facilityId: fac ? fac.dbId : null, deviceType: dd.type, level: dd.level,
            remark: dd.remark || ''
          };
          const done = busy(e.currentTarget, '保存中');
          try {
            if (editing) { body.version = d.version; await CarbonAPI.request('PUT', '/devices/' + d.dbId, body); }
            else await CarbonAPI.request('POST', '/devices', body);
            done(); close(); toast('ok', editing ? '设备已保存' : '设备已创建', body.name);
            await CarbonAPI.reloadQuiet();
          } catch (err) { done(); toast('err', err.message); }
        };
      }
    });
  }

  route('/carbon-facility/device/list', {
    title: '设备管理', group: '设备设施',
    render() {
      let list = nodeDevices();
      if (devState.kw) list = list.filter(d => (d.name + d.code).toLowerCase().includes(devState.kw.toLowerCase()));
      const online = list.filter(d => d.status === 'online').length;
      const sel = devState.sel;
      const tree = (nodes) => nodes.filter(n => n.children).map(n => {
        const kids = n.children.filter(c => c.children);
        return `<div><div class="tree-item ${devState.node === n.id ? 'active' : ''}" data-node="${n.id}">
          <span style="color:${kids.length ? 'var(--ink-400)' : 'var(--ink-300)'}">${kids.length ? '▾' : '·'}</span>${esc(n.name)}</div>
          ${kids.length ? `<div class="tree-children">${tree(n.children)}</div>` : ''}</div>`;
      }).join('');
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">设备管理</h1>
          <p class="page-desc">按设施层级查看设备接入与实时状态，数据每 5 分钟采集</p></div>
          <div class="page-actions">
            <button class="btn" id="d-import">${icon('upload', 15)} 导入</button>
            <button class="btn" id="d-export">${icon('download', 15)} 导出</button>
            <button class="btn btn-primary" id="d-add">${icon('plus', 15)} 新建</button></div></div>

        <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
          <div class="field"><label>设备名称</label><input class="input" id="d-kw" placeholder="输入关键词" value="${esc(devState.kw)}"></div>
          <div class="field"><label>能源种类</label><select class="select"><option>全部</option><option>电力</option><option>天然气</option><option>蒸汽</option></select></div>
          <div class="field"><label>状态</label><select class="select"><option>全部</option><option>在线</option><option>离线</option></select></div>
          <div class="field"><label>&nbsp;</label><div class="toolbar">
            <button class="btn btn-primary" id="d-search">查询</button><button class="btn" id="d-reset">重置</button></div></div>
          <div class="field f-actions"><label>&nbsp;</label>
            <button class="btn btn-danger" id="d-bulk" ${sel.size ? '' : 'disabled'}>批量删除${sel.size ? '（' + sel.size + '）' : ''}</button></div>
        </div></div></section>

        <div class="grid" style="grid-template-columns:minmax(0,240px) minmax(0,1fr);align-items:start">
          <section class="card"><div class="card-head"><h2 class="card-title">设施列表</h2>
            <button class="btn btn-ghost btn-sm right" id="d-refresh">${icon('refresh', 14)}</button></div>
            <div class="card-body"><div class="tree">
              <div class="tree-item ${devState.node === 'all' ? 'active' : ''}" data-node="all">全部设备<span class="cnt">${flatten().length}</span></div>
              ${tree(DB.facilities)}</div></div></section>

          <section class="card"><div class="card-head"><h2 class="card-title">设备列表</h2>
            <span class="tag tag-ok dot">在线 ${online}</span><span class="tag tag-muted dot">离线 ${list.length - online}</span>
            <span class="small muted right">已选 ${sel.size} 项</span></div>
            <div class="table-wrap"><table class="tb"><thead><tr>
              <th style="width:34px"><input type="checkbox" data-d-all></th>
              <!-- TODO(v1.1 单一来源)：本表列定义与后端 DeviceDtos/设备导出表头是两处维护，
                   跨语言（JS ↔ Java）统一需要构建期代码生成，本轮不改；改动任一侧必须同步另一侧。
                   权威定义：platform/backend/src/main/java/com/carbonbal/device/DeviceDtos.java -->
              <th>设备名称</th><th>设备编码</th><th>备注</th><th>企业</th><th>设备类型</th>
              <th>计量级别</th><th>能源种类</th><th>禁用状态</th><th style="width:170px">操作</th></tr></thead>
              <tbody>${list.length ? list.map(d => `<tr data-dev="${d.id}" style="cursor:pointer">
                <td><input type="checkbox" data-d-check="${d.id}" ${sel.has(d.id) ? 'checked' : ''}></td>
                <td><b>${esc(d.name)}</b></td><td>${d.code}</td>
                <td class="muted">—</td><td>${esc(DB.org.short)}</td>
                <td>${esc(d.type)}</td>
                <td><span class="tag ${d.level === '一级' ? 'tag-info' : 'tag-muted'}">${d.level}</span></td>
                <td>${esc(d.energy)}</td>
                <td>${d.disabled ? '<span class="tag tag-muted dot">已禁用</span>' : '<span class="tag tag-ok dot">启用</span>'}</td>
                <td class="actions"><button class="btn btn-sm" data-d-edit="${d.id}">编辑</button>
                  <button class="btn btn-sm btn-ghost" data-d-del="${d.id}">删除</button>
                  <button class="btn btn-sm btn-ghost" data-d-disable="${d.id}">${d.disabled ? '启用' : '禁用'}</button></td></tr>`).join('')
                : `<tr><td colspan="10"><div class="tb-empty">${icon('cpu')}<div>该节点下暂无设备</div></div></td></tr>`}</tbody></table></div>
            ${pager(list.length, 1, 10)}</section>
        </div></div>`;
    },
    mount(root) {
      const sel = devState.sel;
      const syncBulk = () => {
        const b = $('#d-bulk');
        if (b) { b.disabled = sel.size === 0; b.textContent = '批量删除' + (sel.size ? '（' + sel.size + '）' : ''); }
      };
      root.querySelectorAll('[data-node]').forEach(n => n.onclick = () => { devState.node = n.dataset.node; UI.render(); });
      root.querySelectorAll('[data-d-check]').forEach(c => c.onchange = () => {
        c.checked ? sel.add(c.dataset.dCheck) : sel.delete(c.dataset.dCheck); syncBulk();
      });
      const allBox = root.querySelector('[data-d-all]');
      if (allBox) allBox.onchange = () => {
        root.querySelectorAll('[data-d-check]').forEach(c => {
          c.checked = allBox.checked;
          allBox.checked ? sel.add(c.dataset.dCheck) : sel.delete(c.dataset.dCheck);
        });
        syncBulk();
      };
      syncBulk();

      const openDetail = (id) => {
        const d = flatten().find(x => x.id === id);
        if (!d) return;
        const vals = d.status === 'online' ? [12, 8, 36, 42, 38, 26] : [0, 0, 0, 0, 0, 0];
        const max = Math.max(...vals, 1);
        const pts = vals.map((v, i) => `${20 + i * 60},${90 - (v / max) * 64}`).join(' ');
        drawer({ title: d.name, body: `<div class="stack">
          <div class="row">${d.status === 'online' ? '<span class="tag tag-ok dot">在线</span>' : '<span class="tag tag-danger dot">离线</span>'}
            <span class="tag tag-info">${d.level}计量</span><span class="right small muted">${d.code}</span></div>
          <div class="grid grid-2" style="gap:10px">
            <div><div class="small muted">所属设施</div><b>${esc(d.facility)}</b></div>
            <div><div class="small muted">能源种类</div><b>${esc(d.energy)}</b></div>
            <div><div class="small muted">实时功率</div><b class="mono">${d.power ? d.power + ' kW' : '—'}</b></div>
            <div><div class="small muted">今日能耗</div><b class="mono">${d.today ? int(d.today) + ' kWh' : '—'}</b></div></div>
          <div class="hr"></div><div class="small muted">今日负荷曲线（4 小时粒度）</div>
          <svg viewBox="0 0 340 110" class="chart" role="img" aria-label="负荷曲线">
            <line x1="20" y1="90" x2="320" y2="90" stroke="#e6ebf0"/>
            <polyline points="${pts}" fill="none" stroke="#0f7a5a" stroke-width="2.4" stroke-linejoin="round"/>
            ${vals.map((v, i) => `<circle cx="${20 + i * 60}" cy="${90 - (v / max) * 64}" r="3" fill="#fff" stroke="#0f7a5a" stroke-width="2"/>`).join('')}
            ${['00', '04', '08', '12', '16', '20'].map((h, i) => `<text x="${20 + i * 60}" y="104" text-anchor="middle" font-size="9" fill="#94a3b8">${h}:00</text>`).join('')}</svg>
          <div class="hr"></div><button class="btn btn-primary" id="dv-add">${icon('plus', 15)} 补录一条数据</button></div>`,
          onMount(el, close) {
            el.querySelector('#dv-add').onclick = () => {
              modal({ title: '补录设备数据', width: 460,
                body: `<form id="dv-form">
                  <div class="field" data-f="date"><label class="req">数据时间</label><input class="input" type="datetime-local" name="date" value="${new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16)}"><div class="err-msg"></div></div>
                  <div class="field" style="margin-top:10px" data-f="qty"><label class="req">数值（kWh）</label><input class="input" type="number" name="qty" step="0.1" placeholder="如 128.5"><div class="err-msg"></div></div>
                  <div class="field" style="margin-top:10px" data-f="rate"><label class="req">换算率</label><input class="input" type="number" name="rate" value="1" step="0.1"><div class="err-msg"></div></div></form>`,
                foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="dv-save">保存</button>`,
                onMount(w, cl) {
                  const form = w.querySelector('#dv-form');
                  w.querySelector('#dv-save').onclick = async (e) => {
                    const dd = Object.fromEntries(new FormData(form));
                    const setE = (n, m) => { const f = form.querySelector(`[data-f="${n}"]`); f.classList.toggle('error', !!m); f.querySelector('.err-msg').textContent = m || ''; };
                    let bad = false;
                    if (!dd.date) { setE('date', '日期 必填'); bad = true; }
                    if (!dd.qty || Number(dd.qty) <= 0) { setE('qty', '数值 必填'); bad = true; }
                    if (!dd.rate || Number(dd.rate) <= 0) { setE('rate', '换算率 必填'); bad = true; }
                    if (bad) return;
                    const d0 = flatten().find(x => x.id === id);
                    if (!d0) { toast('err', '设备不存在'); return; }
                    const done = busy(e.currentTarget, '保存中');
                    try {
                      await CarbonAPI.request('POST', '/device-readings', {
                        deviceId: d0.dbId, ts: String(dd.date).length === 16 ? dd.date + ':00' : dd.date,
                        qty: Number(dd.qty), unit: d0.unit || 'kWh', rate: Number(dd.rate),
                        source: '手工补录', status: '正常', remark: '设备详情补录',
                        clientRequestId: 'ui-' + Date.now()
                      });
                      done(); cl(); close(); toast('ok', '数据已补录', dd.qty + ' ' + (d0.unit || 'kWh'));
                      await CarbonAPI.reloadQuiet();
                    } catch (err) { done(); toast('err', err.message); }
                  };
                } });
            };
          } });
      };
      root.querySelectorAll('[data-dev]').forEach(tr => tr.onclick = (e) => {
        if (e.target.closest('button') || e.target.tagName === 'INPUT') return;
        openDetail(tr.dataset.dev);
      });
      root.querySelectorAll('[data-d-edit]').forEach(b => b.onclick = (e) => {
        e.stopPropagation();
        const d = flatten().find(x => x.id === b.dataset.dEdit);
        if (d) deviceForm(d);
      });
      root.querySelectorAll('[data-d-del]').forEach(b => b.onclick = async (e) => {
        e.stopPropagation();
        const d = flatten().find(x => x.id === b.dataset.dDel);
        const ok = await confirm({ title: '删除设备', content: `确定删除「${d.name}」？已有读数会被拒绝删除，可改为禁用。`, okText: '删除', danger: true });
        if (!ok) return;
        try {
          await CarbonAPI.request('DELETE', '/devices/' + d.dbId);
          sel.delete(d.id); toast('ok', '设备已删除', d.name);
          await CarbonAPI.reloadQuiet();
        } catch (err) { toast('err', err.message); }
      });
      root.querySelectorAll('[data-d-disable]').forEach(b => b.onclick = async (e) => {
        e.stopPropagation();
        const d = flatten().find(x => x.id === b.dataset.dDisable);
        if (!d.disabled) {
          const ok = await confirm({ title: '禁用设备', content: `禁用后「${d.name}」不再参与核算与预警，确认？`, okText: '禁用' });
          if (!ok) return;
        }
        try {
          await CarbonAPI.request('POST', '/devices/' + d.dbId + (d.disabled ? '/enable' : '/disable'));
          toast('ok', d.disabled ? '已启用' : '已禁用', d.name);
          await CarbonAPI.reloadQuiet();
        } catch (err) { toast('err', err.message); }
      });

      $('#d-search').onclick = () => { devState.kw = $('#d-kw').value.trim(); UI.render(); toast('ok', '查询完成'); };
      $('#d-reset').onclick = () => { devState.kw = ''; sel.clear(); UI.render(); };
      $('#d-kw').onkeydown = e => { if (e.key === 'Enter') $('#d-search').click(); };
      $('#d-refresh').onclick = async (e) => {
        const btn = e.currentTarget;
        btn.classList.add('is-disabled');
        await sleep(700);
        btn.classList.remove('is-disabled');
        const off = flatten().filter(d => d.status === 'offline').length;
        toast(off ? 'warn' : 'ok', '状态已刷新', off ? `${off} 台设备仍离线` : '全部在线');
      };
      $('#d-export').onclick = async (e) => {
        const done = busy(e.currentTarget, '导出中');
        try { await CarbonAPI.download('/export?resource=devices'); done(); }
        catch (err) { done(); toast('err', err.message); }
      };
      $('#d-import').onclick = () => {
        modal({ title: '导入设备台账', width: 540,
          body: `<div class="stack">
            <button class="btn" id="di-tpl" style="justify-content:center">${icon('download', 16)} 下载模板</button>
            <div class="small muted">CSV 文件（UTF-8）</div>
            <label class="btn" style="justify-content:center;height:auto;padding:10px 12px;cursor:pointer">${icon('upload', 16)}
              <span id="di-name">上传 CSV 文件</span><input type="file" id="di-file" accept=".csv" style="display:none"></label>
            <div class="small muted">列顺序：设备编码,设备名称,能源种类,单位,设施编码,设备类型,级别</div>
            <div class="hr" style="margin:8px 0"></div>
            <div class="row small hide" id="di-del-wrap" style="gap:8px"><span class="muted" id="di-file-name"></span>
              <button class="btn btn-sm btn-ghost" id="di-del">删除文件</button></div></div>`,
          foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="di-run">开始导入</button>`,
          onMount(w, c) {
            const f = w.querySelector('#di-file'), n = w.querySelector('#di-name');
            const wrapDel = w.querySelector('#di-del-wrap'), fn = w.querySelector('#di-file-name');
            w.querySelector('#di-tpl').onclick = async (e) => {
              const done = busy(e.currentTarget, '生成中');
              const fac = flatFacilities()[0] || {};
              const rows = ['设备编码,设备名称,能源种类,单位,设施编码,设备类型,级别',
                'EQ-NEW01,示例电表,外购电力,mWh,' + (fac.code || '') + ',电表,主要用能设备'].join('\n');
              const blob = new Blob(['\uFEFF' + rows], { type: 'text/csv;charset=utf-8' });
              const a = document.createElement('a');
              a.href = URL.createObjectURL(blob); a.download = '设备台账导入模板.csv'; a.click();
              URL.revokeObjectURL(a.href); done(); toast('ok', '模板已下载');
            };
            f.onchange = () => { n.textContent = f.files[0] ? '已选择文件' : '上传 CSV 文件'; wrapDel.classList.toggle('hide', !f.files[0]); fn.textContent = f.files[0] ? f.files[0].name : ''; };
            w.querySelector('#di-del').onclick = () => { f.value = ''; n.textContent = '上传 CSV 文件'; wrapDel.classList.add('hide'); };
            w.querySelector('#di-run').onclick = async (e) => {
              if (!f.files[0]) { toast('err', '请先上传 CSV 文件'); return; }
              const done = busy(e.currentTarget, '导入中');
              try {
                const fd = new FormData(); fd.append('file', f.files[0]);
                const r = await CarbonAPI.request('POST', '/devices/import', fd);
                done(); c();
                toast(r.failed ? 'warn' : 'ok', '导入完成', '成功 ' + r.success + ' 台，失败 ' + r.failed + ' 台');
                await CarbonAPI.reloadQuiet();
              } catch (err) { done(); toast('err', err.message); }
            };
          } });
      };
      $('#d-bulk').onclick = async () => {
        const n = sel.size;
        const ok = await confirm({ title: '批量删除设备', content: `将删除 ${n} 台设备，已有读数的会被拒绝，确认？`, okText: '删除', danger: true });
        if (!ok) return;
        let doneN = 0, failN = 0;
        for (const d of flatten().filter(x => sel.has(x.id))) {
          try { await CarbonAPI.request('DELETE', '/devices/' + d.dbId); doneN++; }
          catch (err) { failN++; }
        }
        sel.clear();
        toast(failN ? 'warn' : 'ok', '批量删除完成', '成功 ' + doneN + ' 台，失败 ' + failN + ' 台');
        await CarbonAPI.reloadQuiet();
      };
      $('#d-add').onclick = () => deviceForm(null);
    }
  });

  /* ============ 预警设置（规则台账） ============ */
  const warnState = { kw: '', sel: new Set() };
  route('/common-warning/setting/list', {
    title: '预警设置', group: '预警管理',
    render() {
      const cat = warnState.cat || '全部', lv = warnState.level || '全部';
      const rows = DB.alertRules.filter(r => {
        const c = r.metric === '排放量' ? '排放' : r.metric === '能耗' ? '能耗' : '设备';
        if (cat !== '全部' && c !== cat) return false;
        if (lv !== '全部' && r.level !== lv) return false;
        return true;
      });
      const sel = warnState.sel;
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">预警设置</h1>
          <p class="page-desc">配置预警事件、类别、阈值与冷却时间，停用后不再触发</p></div>
          <div class="page-actions">
            <button class="btn btn-primary" id="w-new">${icon('plus', 15)} 新建</button></div></div>

        <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
          <div class="field"><label>预警类别</label><select class="select" id="w-cat"><option>全部</option><option>排放</option><option>能耗</option><option>设备</option></select></div>
          <div class="field"><label>事件等级</label><select class="select" id="w-level"><option>全部</option><option>紧急</option><option selected>重要</option><option>一般</option></select></div>
          <div class="field"><label>&nbsp;</label><div class="toolbar">
            <button class="btn btn-primary" id="w-search">查询</button><button class="btn" id="w-reset">重置</button></div></div>
          <div class="field f-actions"><label>&nbsp;</label>
            <button class="btn btn-danger" id="w-bulk" ${sel.size ? '' : 'disabled'}>批量删除${sel.size ? '（' + sel.size + '）' : ''}</button></div>
        </div></div></section>

        <section class="card"><div class="card-head"><h2 class="card-title">预警规则</h2>
          <span class="card-sub">${DB.alertRules.filter(r => r.enabled).length}/${DB.alertRules.length} 启用</span>
          <span class="small muted right">已选 ${sel.size} 项</span></div>
          <div class="table-wrap"><table class="tb"><thead><tr>
            <th style="width:34px"><input type="checkbox" data-w-all></th>
            <th>预警事件</th><th>预警类别</th><th>事件等级</th><th>预警条件</th><th>预警值</th>
            <th>冷却(分钟)</th><th>预警描述</th><th>备注</th><th>企业</th><th>状态</th><th style="width:120px">操作</th></tr></thead>
            <tbody>${rows.length ? rows.map(r => `<tr>
              <td><input type="checkbox" data-w-check="${r.id}" ${sel.has(r.id) ? 'checked' : ''}></td>
              <td><b>${esc(r.name)}</b></td>
              <td><span class="tag ${r.metric === '排放量' ? 'tag-info' : r.metric === '能耗' ? 'tag-warn' : 'tag-muted'}">${r.metric === '排放量' ? '排放' : r.metric === '能耗' ? '能耗' : '设备'}</span></td>
              <td>${r.level === '重要' ? '<span class="tag tag-danger">重要</span>' : r.level === '一般' ? '<span class="tag tag-warn">一般</span>' : '<span class="tag tag-muted">次要</span>'}</td>
              <td class="mono">${r.op} ${r.threshold} ${esc(r.unit)}</td>
              <td class="num">${int(r.threshold)}</td>
              <td>${r.cool}</td>
              <td class="muted">${esc(r.metric)}超限提醒</td>
              <td class="muted">—</td>
              <td>${esc(DB.org.short)}</td>
              <td><button class="switch ${r.enabled ? 'on' : ''}" data-toggle="${r.id}" aria-label="启停"></button></td>
              <td class="actions"><button class="btn btn-sm" data-edit-rule="${r.id}">编辑</button>
                <button class="btn btn-sm btn-ghost" data-del-rule="${r.id}">删除</button></td>
            </tr>`).join('') : `<tr><td colspan="12"><div class="tb-empty">${icon('bell')}<div>暂无预警规则</div></div></td></tr>`}</tbody></table></div>
        </section></div>`;
    },
    mount(root) {
      const sel = warnState.sel;
      const syncBulk = () => {
        const b = $('#w-bulk');
        if (b) { b.disabled = sel.size === 0; b.textContent = '批量删除' + (sel.size ? '（' + sel.size + '）' : ''); }
      };
      root.querySelectorAll('[data-w-check]').forEach(c => c.onchange = () => {
        c.checked ? sel.add(c.dataset.wCheck) : sel.delete(c.dataset.wCheck); syncBulk();
      });
      const all = root.querySelector('[data-w-all]');
      if (all) all.onchange = () => {
        root.querySelectorAll('[data-w-check]').forEach(c => {
          c.checked = all.checked;
          all.checked ? sel.add(c.dataset.wCheck) : sel.delete(c.dataset.wCheck);
        });
        syncBulk();
      };
      syncBulk();

      $('#w-search').onclick = () => {
        warnState.cat = $('#w-cat').value; warnState.level = $('#w-level').value;
        UI.render(); toast('ok', '查询完成');
      };
      $('#w-reset').onclick = () => { warnState.cat = '全部'; warnState.level = '全部'; sel.clear(); UI.render(); };

      root.querySelectorAll('[data-toggle]').forEach(b => b.onclick = async () => {
        const r = DB.alertRules.find(x => x.id === b.dataset.toggle);
        if (r.enabled) {
          const ok = await confirm({ title: '禁用规则', content: `禁用后「${r.name}」不再触发预警，确认？`, okText: '禁用' });
          if (!ok) return;
        }
        try {
          await CarbonAPI.request('PUT', '/alert-rules/' + r.dbId, {
            name: r.name, metric: r.metric, op: r.op, threshold: r.threshold, unit: r.unit,
            cool: r.cool, level: r.level, enabled: r.enabled ? 0 : 1,
            remark: r.remark || '', version: r.version
          });
          toast('ok', r.enabled ? '规则已禁用' : '规则已启用', r.name);
          await CarbonAPI.reloadQuiet();
        } catch (err) { toast('err', err.message); }
      });
      root.querySelectorAll('[data-del-rule]').forEach(b => b.onclick = async () => {
        const r = DB.alertRules.find(x => x.id === b.dataset.delRule);
        const ok = await confirm({ title: '删除规则', content: `确定删除「${r.name}」？删除后不可恢复。`, okText: '删除', danger: true });
        if (!ok) return;
        try {
          await CarbonAPI.request('DELETE', '/alert-rules/' + r.dbId);
          sel.delete(r.id); toast('ok', '规则已删除', r.name);
          await CarbonAPI.reloadQuiet();
        } catch (err) { toast('err', err.message); }
      });
      root.querySelectorAll('[data-edit-rule]').forEach(b => b.onclick = () => {
        const r = DB.alertRules.find(x => x.id === b.dataset.editRule);
        modal({ title: '编辑', width: 520, body: `<form id="rf">
          <div class="field"><label class="req">阈值</label><input class="input" name="threshold" type="number" value="${r.threshold}"><div class="small muted">单位：${esc(r.unit)}</div></div>
          <div class="field" style="margin-top:10px"><label>冷却时间</label><input class="input" name="cool" type="number" value="${r.cool}"></div></form>`,
          foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="rf-save">保存</button>`,
          onMount(w, c) {
            w.querySelector('#rf-save').onclick = async (e) => {
              const d = Object.fromEntries(new FormData(w.querySelector('#rf')));
              if (!d.threshold || Number(d.threshold) <= 0) { toast('err', '阈值必须大于 0'); return; }
              const done = busy(e.currentTarget, '保存中');
              try {
                await CarbonAPI.request('PUT', '/alert-rules/' + r.dbId, {
                  name: r.name, metric: r.metric, op: r.op, threshold: Number(d.threshold),
                  unit: r.unit, cool: Number(d.cool) || r.cool, level: r.level,
                  enabled: r.enabled ? 1 : 0, remark: r.remark || '', version: r.version
                });
                done(); c(); toast('ok', '规则已更新', r.name);
                await CarbonAPI.reloadQuiet();
              } catch (err) { done(); toast('err', err.message); }
            };
          } });
      });
      $('#w-bulk').onclick = async () => {
        const n = sel.size;
        const ok = await confirm({ title: '批量删除规则', content: `将删除 ${n} 条规则，确认？`, okText: '删除', danger: true });
        if (!ok) return;
        try {
          const targets = DB.alertRules.filter(r => sel.has(r.id));
          for (const r of targets) await CarbonAPI.request('DELETE', '/alert-rules/' + r.dbId);
          sel.clear(); toast('ok', '已删除 ' + n + ' 条规则');
          await CarbonAPI.reloadQuiet();
        } catch (err) { toast('err', err.message); }
      };
      $('#w-new').onclick = () => {
        modal({ title: '新建预警规则', width: 540, body: `<form id="wn">
          <div class="field" data-f="event"><label class="req">预警事件</label>
            <select class="select" name="event"><option value=""></option>${DB.warningEvents.map(e => `<option>${e.name}</option>`).join('')}</select><div class="err-msg"></div></div>
          <div class="grid grid-2" style="margin-top:10px">
            <div class="field"><label class="req">事件等级</label><select class="select" name="level"><option>紧急</option><option selected>重要</option><option>一般</option></select></div>
            <div class="field"><label class="req">预警条件</label><select class="select" name="op"><option selected>大于</option><option>小于</option><option>大于等于</option><option>小于等于</option><option>等于</option></select></div></div>
          <div class="grid grid-2" style="margin-top:10px">
            <div class="field" data-f="threshold"><label class="req">预警值</label><input class="input" name="threshold" type="number" placeholder="如 600"><div class="err-msg"></div></div>
            <div class="field"><label>冷却时间</label><input class="input" name="cool" type="number" value="60"></div></div>
          <div class="field" style="margin-top:10px"><label>预警描述</label><textarea class="input" name="desc" placeholder="触发后的说明文案"></textarea></div>
          <div class="field" style="margin-top:10px"><label>备注</label><textarea class="input" name="remark"></textarea></div></form>`,
          foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="wn-save">保存</button>`,
          onMount(w, c) {
            const form = w.querySelector('#wn');
            w.querySelector('#wn-save').onclick = async (e) => {
              const d = Object.fromEntries(new FormData(form));
              const setE = (n, m) => { const f = form.querySelector(`[data-f="${n}"]`); f.classList.toggle('error', !!m); f.querySelector('.err-msg').textContent = m || ''; };
              let bad = false;
              if (!d.event) { setE('event', '预警事件 必填'); bad = true; }
              if (!d.threshold || Number(d.threshold) <= 0) { setE('threshold', '预警值 必填'); bad = true; }
              if (bad) return;
              const done = busy(e.currentTarget, '创建中');
              const ev = DB.warningEvents.find(x => x.name === d.event) || {};
              try {
                await CarbonAPI.request('POST', '/alert-rules', {
                  name: d.event, metric: ev.metric || '能耗', op: d.op, threshold: Number(d.threshold),
                  unit: ev.unit || 'tce', cool: Number(d.cool) || 720, level: d.level,
                  enabled: 1, remark: d.remark || ''
                });
                done(); c(); toast('ok', '规则已创建', d.event);
                await CarbonAPI.reloadQuiet();
              } catch (err) { done(); toast('err', err.message); }
            };
          } });
      };
    }
  });

  /* ============ 预警记录（服务端分页） ============ */
  const recState = {
    page: 1, size: 10, total: 0, rows: [], stats: null,
    kw: '', level: '', read: '', status: '', metric: '', device: '', from: '', to: '',
    sel: new Set(), loading: false, loaded: false
  };
  const recMap = (r) => Object.assign({}, r, {
    dbId: r.id, id: 'A-' + r.id, read: r.isRead === 1,
    at: (r.occurredAt || '').replace('T', ' ').slice(0, 16),
    device: r.deviceCode || '',
    limit: r.threshold == null ? '' : String(r.threshold),
    value: (r.metricValue == null ? '' : String(r.metricValue)) + (r.unit || '')
  });
  const REC_STATUS = { '待处理': 'open', '已处理': 'handled', '已忽略': 'ignored' };

  async function recLoad() {
    const qs = new URLSearchParams({ page: String(recState.page), size: String(recState.size) });
    if (recState.kw) qs.set('keyword', recState.kw);
    if (recState.level) qs.set('level', recState.level);
    if (recState.read === '未读') qs.set('isRead', '0');
    if (recState.read === '已读') qs.set('isRead', '1');
    if (REC_STATUS[recState.status]) qs.set('status', REC_STATUS[recState.status]);
    if (recState.metric) qs.set('metric', recState.metric);
    if (recState.device) qs.set('device', recState.device);
    if (recState.from) qs.set('from', recState.from + 'T00:00:00');
    if (recState.to) qs.set('to', recState.to + 'T23:59:59');
    try {
      const r = await CarbonAPI.request('GET', '/alert-records?' + qs.toString());
      recState.rows = (r.rows || []).map(recMap);
      recState.total = r.total || 0;
      recState.loaded = true;
      recState.stats = await CarbonAPI.request('GET', '/alert-records/stats');
    } catch (e) { toast('err', e.message); }
  }

  route('/common-warning/record/list', {
    title: '预警记录', group: '预警管理',
    render() {
      const sel = recState.sel;
      const rows = recState.rows;
      const st = recState.stats || {};
      const canWrite = !!(window.CarbonAPI && CarbonAPI.canWrite && CarbonAPI.canWrite());
      const admin = !!(window.CarbonAPI && CarbonAPI.isAdmin && CarbonAPI.isAdmin());
      const levelTag = l => l === 'high' ? '<span class="tag tag-danger">重要</span>' : l === 'mid' ? '<span class="tag tag-warn">一般</span>' : '<span class="tag tag-muted">次要</span>';
      const statusTag = s => s === 'open' ? '<span class="tag tag-warn dot">待处理</span>' : s === 'handled' ? '<span class="tag tag-ok dot">已处理</span>' : '<span class="tag tag-muted dot">已忽略</span>';
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">预警记录</h1>
          <p class="page-desc">规则触发后的记录流，支持标记已读、处理与批量删除</p></div>
          <div class="page-actions">
            ${canWrite ? '<button class="btn" id="rec-readall">全部已读</button>' : ''}</div></div>

        <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
          <div class="field"><label>事件名称</label><input class="input" id="rec-kw" placeholder="输入事件/设备" value="${esc(recState.kw)}"></div>
          <div class="field"><label>等级</label>${UI.selectMenu({ id: 'rec-level', value: recState.level, options: [{ label: '全部', value: '' }, { label: '重要', value: 'high' }, { label: '一般', value: 'mid' }, { label: '次要', value: 'low' }] })}</div>
          <div class="field"><label>已读状态</label>${UI.selectMenu({ id: 'rec-read', value: recState.read, options: ['全部', '未读', '已读'] })}</div>
          <div class="field" style="min-width:236px" data-recrange><label>记录日期</label>${UI.rangePicker({ mode: 'date', from: recState.from, to: recState.to, ph: '开始日期 ~ 结束日期' })}</div>
          <div class="field"><label>处理状态</label>${UI.selectMenu({ id: 'rec-status', value: recState.status, options: ['全部', '待处理', '已处理', '已忽略'] })}</div>
          <div class="field"><label>指标</label>${UI.selectMenu({ id: 'rec-metric', value: recState.metric, options: ['全部', '排放量', '能耗', '离线时长'] })}</div>
          <div class="field"><label>设备编码</label><input class="input" id="rec-device" placeholder="如 EQ-A102" value="${esc(recState.device)}"></div>
          <div class="field"><label>&nbsp;</label><div class="toolbar">
            <button class="btn btn-primary" id="rec-search">查询</button><button class="btn" id="rec-reset">重置</button></div></div>
          ${admin ? `<div class="field f-actions"><label>&nbsp;</label>
            <button class="btn btn-danger" id="rec-bulk" ${sel.size ? '' : 'disabled'}>批量删除${sel.size ? '（' + sel.size + '）' : ''}</button></div>` : ''}
        </div></div></section>

        <div class="grid grid-4" style="margin-bottom:13px">${[
          ['未读预警', st.unread || 0, '条'],
          ['待处理预警', st.open || 0, '条'],
          ['紧急(1级)待处理', st.highOpen || 0, '条'],
          ['重要(2级)待处理', st.midOpen || 0, '条'],
        ].map(([l, v, u]) => `<div class="kpi"><div class="kpi-label">${l}</div>
          <div class="kpi-value">${v}<small>${u}</small></div><div class="kpi-foot">实时统计</div></div>`).join('')}</div>

        <section class="card"><div class="card-head"><h2 class="card-title">记录</h2>
          <span class="card-sub">${recState.loaded ? '共 ' + recState.total + ' 条' : '加载中…'}</span>
          <span class="small muted right">已选 ${sel.size} 项</span></div>
          <div class="table-wrap"><table class="tb"><thead><tr>
            <th style="width:34px"><input type="checkbox" data-r-all></th>
            <th>未读</th><th>记录日期</th><th>等级</th><th>事件名称</th><th>企业</th><th>设备编码</th>
            <th>指标</th><th>阈值</th><th>条件</th><th>记录值</th><th>处理状态</th><th>处理人/时间</th><th style="width:130px">操作</th></tr></thead>
            <tbody>${rows.length ? rows.map(r => `<tr>
              <td><input type="checkbox" data-r-check="${r.id}" ${sel.has(r.id) ? 'checked' : ''}></td>
              <td>${r.read ? '<span class="tag tag-muted">已读</span>' : '<span class="tag tag-danger">未读</span>'}</td>
              <td>${esc(r.at)}</td><td>${levelTag(r.level)}</td><td><b>${esc(r.title)}</b></td>
              <td>${esc((DB.org && DB.org.short) || '')}</td><td>${esc(r.device)}</td>
              <td>${esc(r.metric || '—')}</td>
              <td class="num">${esc(r.limit)}</td><td class="mono">&gt;</td><td class="num">${esc(r.value)}</td>
              <td>${statusTag(r.status)}</td>
              <td>${r.status === 'open' ? '—' : esc(r.handledBy || '—') + '<div class="small muted">' + esc((r.handledAt || '').replace('T', ' ').slice(0, 16)) + '</div>'}</td>
              <td class="actions">${[
                r.status === 'open' && canWrite ? `<button class="btn btn-sm" data-handle="${r.id}">处理</button>` : '',
                canWrite && !r.read ? `<button class="btn btn-sm btn-ghost" data-read="${r.id}">标记已读</button>` : ''
              ].join('') || '<span class="muted">—</span>'}</td>
            </tr>`).join('') : `<tr><td colspan="14"><div class="tb-empty">${icon('bell')}<div>${recState.loaded ? '该条件暂无记录' : '加载中…'}</div></div></td></tr>`}</tbody></table></div>
          ${pager(recState.total, recState.page, recState.size)}
        </section></div>`;
    },
    mount(root) {
      const sel = recState.sel;
      const paint = async () => { await recLoad(); UI.render(); };
      if (!recState.loaded && !recState.loading) {
        recState.loading = true;
        recLoad().finally(() => { recState.loading = false; UI.render(); });
      }
      const syncBulk = () => {
        const b = $('#rec-bulk');
        if (b) { b.disabled = sel.size === 0; b.textContent = '批量删除' + (sel.size ? '（' + sel.size + '）' : ''); }
      };
      root.querySelectorAll('[data-r-check]').forEach(c => c.onchange = () => {
        c.checked ? sel.add(c.dataset.rCheck) : sel.delete(c.dataset.rCheck); syncBulk();
      });
      const all = root.querySelector('[data-r-all]');
      if (all) all.onchange = () => {
        root.querySelectorAll('[data-r-check]').forEach(c => {
          c.checked = all.checked;
          all.checked ? sel.add(c.dataset.rCheck) : sel.delete(c.dataset.rCheck);
        });
        syncBulk();
      };
      syncBulk();

      UI.bindRangePickers(root);
      UI.bindSelects(root);
      const readFilters = () => {
        recState.kw = ($('#rec-kw') || {}).value ? $('#rec-kw').value.trim() : '';
        recState.level = ($('#rec-level') || {}).value || '';
        recState.read = ($('#rec-read') || {}).value || '';
        recState.status = ($('#rec-status') || {}).value || '';
        recState.metric = ($('#rec-metric') || {}).value || '';
        recState.device = ($('#rec-device') || {}).value ? $('#rec-device').value.trim() : '';
        const rb = root.querySelector('[data-recrange] [data-rp]');
        recState.from = rb ? rb.dataset.from || '' : '';
        recState.to = rb ? rb.dataset.to || '' : '';
      };
      $('#rec-search').onclick = async () => {
        readFilters(); recState.page = 1; sel.clear();
        await paint();
        toast('ok', '查询完成', recState.total + ' 条');
      };
      $('#rec-reset').onclick = async () => {
        Object.assign(recState, { kw: '', level: '', read: '', status: '', metric: '', device: '', from: '', to: '', page: 1 });
        sel.clear();
        await paint();
      };
      $('#rec-kw').onkeydown = e => { if (e.key === 'Enter') $('#rec-search').click(); };
      $('#rec-device').onkeydown = e => { if (e.key === 'Enter') $('#rec-search').click(); };
      bindPager(root, async n => { recState.page = n; sel.clear(); await paint(); });

      root.querySelectorAll('[data-read]').forEach(b => b.onclick = async () => {
        const a = recState.rows.find(x => x.id === b.dataset.read);
        if (!a) return;
        if (a.read) { toast('warn', '该记录已是已读'); return; }
        try { await CarbonAPI.request('POST', '/alert-records/' + a.dbId + '/read'); toast('ok', '已标记已读', a.id); await paint(); }
        catch (err) { toast('err', err.message); }
      });
      root.querySelectorAll('[data-handle]').forEach(b => b.onclick = () => {
        const a = recState.rows.find(x => x.id === b.dataset.handle);
        modal({ title: '处理预警', width: 500, body: `<div class="stack">
          <div class="small muted">${esc(a.title)} · ${esc(a.value)}</div>
          <div class="field"><label class="req">处理方式</label><select class="select" id="h-type"><option value="fix">已现场处置</option><option value="ignore">误报，忽略</option><option value="plan">列入整改计划</option></select></div>
          <div class="field"><label>处理说明</label><textarea class="input" id="h-note" placeholder="简要说明原因与措施"></textarea></div></div>`,
          foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="h-save">提交</button>`,
          onMount(w, c) {
            w.querySelector('#h-save').onclick = async (e) => {
              const type = w.querySelector('#h-type').value, note = w.querySelector('#h-note').value.trim();
              if (type !== 'ignore' && !note) { toast('err', '请填写处理说明'); return; }
              const done = busy(e.currentTarget, '提交中');
              const label = type === 'ignore' ? '误报忽略' : type === 'plan' ? '列入整改计划' : '已现场处置';
              try {
                await CarbonAPI.request('POST', '/alert-records/' + a.dbId + '/handle', { remark: label + (note ? '：' + note : '') });
                done(); c(); toast('ok', '预警已处理', a.id);
                await paint();
              } catch (err) { done(); toast('err', err.message); }
            };
          } });
      });
      const readAll = $('#rec-readall');
      if (readAll) readAll.onclick = async () => {
        const ok = await confirm({ title: '全部已读', content: '将全部记录标记为已读？待处理状态不变。', okText: '标记已读' });
        if (!ok) return;
        try { await CarbonAPI.request('POST', '/alert-records/read-all'); toast('ok', '已全部标记已读'); await paint(); }
        catch (err) { toast('err', err.message); }
      };
      const bulk = $('#rec-bulk');
      if (bulk) bulk.onclick = async () => {
        const ids = recState.rows.filter(a => sel.has(a.id)).map(a => a.dbId);
        if (!ids.length) return;
        const ok = await confirm({ title: '批量删除记录', content: `将删除 ${ids.length} 条预警记录，确认？`, okText: '删除', danger: true });
        if (!ok) return;
        try {
          await CarbonAPI.request('POST', '/alert-records/batch-delete', { ids });
          sel.clear(); toast('ok', '已删除 ' + ids.length + ' 条记录');
          await paint();
        } catch (err) { toast('err', err.message); }
      };
    }
  });

  /* ============ 在线监测（竞品为空目录，1:1 保留占位） ============ */
  route('/carbon-monitor', {
    title: '在线监测', group: '在线监测',
    render() {
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">在线监测</h1>
          <p class="page-desc">该目录在竞品中无子菜单，页面为空白</p></div></div>
        <section class="card"><div class="card-body">
          <div class="tb-empty" style="padding:64px 16px">
            ${icon('cpu', 40)}
            <div style="font-size:15px;font-weight:600;color:var(--ink-700);margin-top:8px">模块未开通</div>
            <div class="small">竞品侧该一级目录下无任何页面入口（访问 404），此处按 1:1 复刻保留</div>
            <div class="row" style="justify-content:center;margin-top:14px">
              <a class="btn btn-sm" href="#/carbon-facility/device/list">前往设备管理</a>
            </div>
          </div>
        </div></section></div>`;
    }
  });
})();
