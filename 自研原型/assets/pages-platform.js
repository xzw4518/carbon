/* 平台管理（超级管理员）：租户开通、授权期、套餐席位、进入租户 */
(function () {
  'use strict';
  const { $, $$, esc, icon, toast, modal, confirm, busy, pager, bindPager, route } = UI;

  const st = {
    page: 1, size: 10, total: 0, rows: [], stats: null,
    kw: '', status: '', expiring: '', loaded: false, loading: false, seq: 0
  };

  const PLAN = [['basic', '基础版'], ['standard', '标准版'], ['enterprise', '企业版']];
  const planLabel = (v) => (PLAN.find(p => p[0] === v) || [, v || '—'])[1];
  const localDate = (d = new Date()) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  const today = () => localDate();
  const plusMonths = (n) => {
    const d = new Date();
    d.setMonth(d.getMonth() + n);
    return localDate(d);
  };

  const STATUS = {
    active: { text: '正常', cls: 'tag-ok' },
    pending: { text: '未生效', cls: 'tag-info' },
    suspended: { text: '已停用', cls: 'tag-warn' },
    expired: { text: '已到期', cls: 'tag-muted' }
  };
  const statusTag = (s) => {
    const x = STATUS[s] || { text: s || '—', cls: 'tag-muted' };
    return `<span class="tag ${x.cls} dot">${x.text}</span>`;
  };

  const remain = (d) => {
    if (d === null || d === undefined) return '<span class="muted">长期</span>';
    if (d < 0) return `<span style="color:#B4472B">已过期 ${-d} 天</span>`;
    if (d === 0) return '<span style="color:#B4472B">今天到期</span>';
    if (d <= 30) return `<span style="color:#B45309">${d} 天</span>`;
    return `${d} 天`;
  };

  async function load() {
    const seq = ++st.seq;
    st.loading = true;
    const qs = new URLSearchParams({ page: String(st.page), size: String(st.size) });
    if (st.kw) qs.set('keyword', st.kw);
    if (st.status) qs.set('status', st.status);
    if (st.expiring) qs.set('expiring', st.expiring);
    try {
      const [list, stats] = await Promise.all([
        CarbonAPI.request('GET', '/platform/tenants?' + qs.toString()),
        CarbonAPI.request('GET', '/platform/stats')
      ]);
      if (seq !== st.seq) return;
      st.rows = list.rows || [];
      st.total = list.total || 0;
      st.stats = stats;
      st.loaded = true;
      if (st.page > 1 && !st.rows.length && st.total > 0) {
        st.page -= 1;
        return load();
      }
    } catch (e) {
      toast('err', e.message);
    } finally {
      if (seq === st.seq) st.loading = false;
    }
  }

  const repaint = () => UI.render();

  async function reload() {
    await load();
    repaint();
  }

  /* ==================== 开通向导 ==================== */

  function createWizard() {
    const data = {
      name: '', code: '', tenantCode: '', adminName: '', adminPhone: '',
      plan: 'standard', seatLimit: 20, licenseStartAt: today(), licenseEndAt: plusMonths(12)
    };
    let step = 0;

    const stepBody = () => {
      if (step === 0) {
        return `<div class="grid grid-2" style="gap:0 14px">
          <div class="field" style="grid-column:1/-1"><label class="req">企业名称</label>
            <input class="input" data-k="name" value="${esc(data.name)}" placeholder="营业执照上的全称"></div>
          <div class="field"><label>企业编码</label>
            <input class="input" data-k="code" value="${esc(data.code)}" placeholder="留空自动生成"></div>
          <div class="field"><label>租户短码</label>
            <input class="input" data-k="tenantCode" value="${esc(data.tenantCode)}" maxlength="16" placeholder="运维识别用，可空"></div>
        </div>
        <div class="small muted" style="margin-top:8px">开通后自动创建默认厂区；默认角色【运营】在角色模块上线后自动补齐。</div>`;
      }
      if (step === 1) {
        return `<div class="grid grid-2" style="gap:0 14px">
          <div class="field"><label class="req">管理员姓名</label>
            <input class="input" data-k="adminName" value="${esc(data.adminName)}" placeholder="如 陈立"></div>
          <div class="field"><label class="req">管理员手机号</label>
            <input class="input" data-k="adminPhone" value="${esc(data.adminPhone)}" maxlength="11" inputmode="numeric" placeholder="11 位手机号"></div>
        </div>
        <div class="small muted" style="margin-top:8px">该手机号未注册时会创建账号并返回一次性初始密码；已注册则直接成为租户管理员。</div>`;
      }
      if (step === 2) {
        return `<div class="grid grid-2" style="gap:0 14px">
          <div class="field"><label class="req">授权开始</label><input class="input" type="date" data-k="licenseStartAt" value="${esc(data.licenseStartAt)}"></div>
          <div class="field"><label class="req">授权到期</label><input class="input" type="date" data-k="licenseEndAt" value="${esc(data.licenseEndAt)}"></div>
          <div class="field"><label>套餐</label><select class="select" data-k="plan">
            ${PLAN.map(([v, t]) => `<option value="${v}" ${v === data.plan ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
          <div class="field"><label>席位数</label><input class="input" type="number" min="1" data-k="seatLimit" value="${data.seatLimit}"></div>
        </div>
        <div class="small muted" style="margin-top:8px">到期即阻断该租户全部账号登录，无宽限期；续期后立即恢复。</div>`;
      }
      return `<div class="stack" style="gap:9px">
        ${[['企业名称', data.name], ['企业编码', data.code || '自动生成'], ['管理员', (data.adminName || '') + ' ' + data.adminPhone],
          ['授权期', data.licenseStartAt + ' ~ ' + data.licenseEndAt], ['套餐', planLabel(data.plan) + ' · ' + data.seatLimit + ' 席']]
          .map(([k, v]) => `<div class="between" style="padding:7px 0;border-bottom:1px dashed var(--line)">
            <span class="muted small">${k}</span><b>${esc(v || '—')}</b></div>`).join('')}
      </div>
      <div class="small muted" style="margin-top:10px">提交后生成租户、租户管理员、默认厂区，并写平台审计。</div>`;
    };

    modal({
      title: '开通租户', width: 620,
      body: `<div class="row" id="wz-steps" style="gap:6px;margin-bottom:16px">
        ${['企业信息', '管理员', '授权与套餐', '确认'].map((t, i) => `<div class="wz-step" data-i="${i}"
          style="flex:1;text-align:center;font-size:12.5px;padding:6px 0;border-radius:8px;background:#F2F7F4;color:var(--ink-500)">
          <b>${i + 1}</b> ${t}</div>`).join('')}
      </div>
      <div id="wz-body"></div>`,
      foot: `<button class="btn" data-close>取消</button>
        <button class="btn" id="wz-prev">上一步</button>
        <button class="btn btn-primary" id="wz-next">下一步</button>`,
      onMount(wrap, close) {
        const body = $('#wz-body', wrap);
        const prev = $('#wz-prev', wrap);
        const next = $('#wz-next', wrap);

        const paint = () => {
          body.innerHTML = stepBody();
          $$('#wz-steps .wz-step', wrap).forEach(el => {
            const i = Number(el.dataset.i);
            el.style.background = i === step ? '#D8F2E8' : '#F2F7F4';
            el.style.color = i === step ? '#0C5A44' : 'var(--ink-500)';
          });
          prev.style.visibility = step === 0 ? 'hidden' : 'visible';
          next.textContent = step === 3 ? '确认开通' : '下一步';
          $$('[data-k]', body).forEach(inp => {
            inp.onchange = inp.oninput = () => { data[inp.dataset.k] = inp.value.trim(); };
          });
        };

        const collect = () => {
          $$('[data-k]', body).forEach(inp => { data[inp.dataset.k] = inp.value.trim(); });
        };

        const validate = () => {
          if (step === 0 && !data.name) { toast('err', '请填写企业名称'); return false; }
          if (step === 1 && !/^1\d{10}$/.test(data.adminPhone)) { toast('err', '管理员手机号格式不正确'); return false; }
          if (step === 2) {
            if (!data.licenseStartAt || !data.licenseEndAt) { toast('err', '请填写授权开始与到期'); return false; }
            if (data.licenseEndAt < data.licenseStartAt) { toast('err', '到期日期不能早于开始日期'); return false; }
            if (Number(data.seatLimit) < 1) { toast('err', '席位数至少 1'); return false; }
          }
          return true;
        };

        prev.onclick = () => { if (step > 0) { collect(); step--; paint(); } };
        next.onclick = async (e) => {
          if (!validate()) return;
          if (step < 3) { collect(); step++; paint(); return; }
          const done = busy(e.currentTarget, '开通中');
          try {
            const r = await CarbonAPI.request('POST', '/platform/tenants', {
              name: data.name,
              code: data.code || null,
              tenantCode: data.tenantCode || null,
              adminName: data.adminName || null,
              adminPhone: data.adminPhone,
              plan: data.plan,
              seatLimit: Number(data.seatLimit),
              licenseStartAt: data.licenseStartAt,
              licenseEndAt: data.licenseEndAt
            });
            done(); close();
            modal({
              title: '租户已开通', width: 480,
              body: `<div class="small" style="margin-bottom:10px">企业 <b>${esc(data.name)}</b>（ID ${r.orgId}）已创建，授权至 ${esc(r.licenseEndAt)}。</div>
                ${r.tempPassword ? `<div style="background:#F0FBF5;border:1px solid #BFE6CB;border-radius:10px;padding:12px">
                  <div class="small muted">管理员初始密码（仅显示一次）</div>
                  <div class="mono" style="margin-top:6px;font-size:15px">${esc(r.tempPassword)}</div></div>`
                : '<div class="small muted">该手机号已有账号，直接成为租户管理员，无需新密码。</div>'}`,
              foot: '<button class="btn btn-primary" data-close>我已保存</button>'
            });
            st.page = 1;
            await refreshPlatform();
          } catch (err) { done(); toast('err', err.message); }
        };
        paint();
      }
    });
  }

  /* ==================== 续期 / 设置 / 状态 ==================== */

  function renewForm(t) {
    modal({
      title: '续期 · ' + t.name, width: 460,
      body: `<div class="small muted" style="margin-bottom:10px">当前授权至 <b>${esc(t.licenseEndAt || '长期')}</b>，剩余 ${t.remainDays == null ? '长期' : t.remainDays + ' 天'}</div>
        <div class="field"><label>顺延</label><select class="select" id="rn-months">
          <option value="3">3 个月</option><option value="6">6 个月</option>
          <option value="12" selected>12 个月</option><option value="24">24 个月</option>
          <option value="custom">指定到期日</option></select></div>
        <div class="field" id="rn-custom" style="display:none"><label>到期日</label>
          <input class="input" type="date" id="rn-date" value="${esc(t.licenseEndAt || plusMonths(12))}"></div>
        <div class="small muted">已过期的租户按今天起算；续期后状态恢复为「正常」。</div>`,
      foot: '<button class="btn" data-close>取消</button><button class="btn btn-primary" id="rn-ok">确认续期</button>',
      onMount(wrap, close) {
        const sel = $('#rn-months', wrap);
        const box = $('#rn-custom', wrap);
        sel.onchange = () => { box.style.display = sel.value === 'custom' ? '' : 'none'; };
        $('#rn-ok', wrap).onclick = async (e) => {
          const body = sel.value === 'custom'
            ? { licenseEndAt: $('#rn-date', wrap).value }
            : { months: Number(sel.value) };
          if (sel.value === 'custom' && !body.licenseEndAt) { toast('err', '请选择到期日'); return; }
          const done = busy(e.currentTarget, '续期中');
          try {
            await CarbonAPI.request('POST', '/platform/tenants/' + t.orgId + '/renew', body);
            done(); close(); toast('ok', '已续期', t.name);
            await refreshPlatform();
          } catch (err) { done(); toast('err', err.message); }
        };
      }
    });
  }

  function editForm(t) {
    modal({
      title: '租户设置 · ' + t.name, width: 520,
      body: `<form id="te-form"><div class="grid grid-2" style="gap:0 14px">
        <div class="field" style="grid-column:1/-1"><label class="req">企业名称</label>
          <input class="input" name="name" value="${esc(t.name)}"></div>
        <div class="field"><label>套餐</label><select class="select" name="plan">
          ${PLAN.map(([v, x]) => `<option value="${v}" ${v === t.plan ? 'selected' : ''}>${x}</option>`).join('')}</select></div>
        <div class="field"><label>席位数</label><input class="input" type="number" min="1" name="seatLimit" value="${t.seatLimit || 20}"></div>
        <div class="field"><label>授权开始</label><input class="input" type="date" name="licenseStartAt" value="${esc(t.licenseStartAt || '')}"></div>
        <div class="field"><label>授权到期</label><input class="input" type="date" name="licenseEndAt" value="${esc(t.licenseEndAt || '')}"></div>
      </div>
      <div class="small muted" style="margin-top:10px">席位数不能小于当前成员数（已用 ${t.members} 席）。停用与恢复在列表操作列。</div></form>`,
      foot: '<button class="btn" data-close>取消</button><button class="btn btn-primary" id="te-save">保存</button>',
      onMount(wrap, close) {
        $('#te-save', wrap).onclick = async (e) => {
          const d = Object.fromEntries(new FormData($('#te-form', wrap)));
          if (d.licenseEndAt && d.licenseStartAt && d.licenseEndAt < d.licenseStartAt) {
            toast('err', '到期日期不能早于开始日期'); return;
          }
          const done = busy(e.currentTarget, '保存中');
          try {
            await CarbonAPI.request('PUT', '/platform/tenants/' + t.orgId, {
              name: d.name, plan: d.plan, seatLimit: Number(d.seatLimit),
              licenseStartAt: d.licenseStartAt || null,
              licenseEndAt: d.licenseEndAt || null
            });
            done(); close(); toast('ok', '已保存');
            await refreshPlatform();
          } catch (err) { done(); toast('err', err.message); }
        };
      }
    });
  }

  async function enterTenant(t) {
    if (!t) return;
    const warn = t.status === 'expired' || t.status === 'suspended'
      ? `该租户当前为「${STATUS[t.status].text}」状态，超级管理员仍可进入。` : '';
    const ok = await confirm({
      title: '进入租户',
      content: `将以超级管理员身份进入「${t.name}」的业务上下文，可查看和管理该租户全部数据。${warn}`,
      okText: '进入'
    });
    if (!ok) return;
    try {
      await CarbonAPI.request('POST', '/auth/enter-tenant', { orgId: t.orgId });
      location.href = '/app/';
    } catch (e) { toast('err', e.message); }
  }

  async function changeStatus(t, action) {
    if (!t) return;
    const map = {
      suspend: { title: '停用租户', content: `停用「${t.name}」后，该租户所有账号立即无法登录、已登录会话失效。`, ok: '停用' },
      resume: { title: '恢复租户', content: `恢复「${t.name}」后，租户成员可正常登录（授权期仍需有效）。`, ok: '恢复' }
    };
    const cfg = map[action];
    const ok = await confirm({ title: cfg.title, content: cfg.content, okText: cfg.ok, danger: action === 'suspend' });
    if (!ok) return;
    try {
      await CarbonAPI.request('POST', '/platform/tenants/' + t.orgId + '/' + action, {});
      toast('ok', action === 'suspend' ? '已停用' : '已恢复', t.name);
      await refreshPlatform();
    } catch (e) { toast('err', e.message); }
  }

  /* ==================== 页面 ==================== */

  route('/platform/tenants', {
    title: '租户管理', group: '平台管理',
    render() {
      const s = st.stats || {};
      const rows = st.rows;
      const seatUsed = s.seatUsed == null ? 0 : s.seatUsed;
      const seatTotal = s.seatTotal == null ? 0 : s.seatTotal;
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">租户管理</h1>
          <p class="page-desc">开通租户、控制授权期与席位；到期即阻断该租户全部账号登录</p></div>
          <div class="page-actions"><button class="btn btn-primary" id="t-new">${icon('plus', 15)} 开通租户</button></div></div>

        <div class="grid grid-4" style="margin-bottom:13px">
          <div class="kpi"><div class="kpi-label">租户总数</div><div class="kpi-value">${s.tenants || 0}<small>家</small></div></div>
          <div class="kpi"><div class="kpi-label">正常租户</div><div class="kpi-value">${s.activeTenants || 0}<small>家</small></div></div>
          <div class="kpi"><div class="kpi-label">30 日内到期</div>
            <div class="kpi-value" style="${s.expiring30 ? 'color:#B45309' : ''}">${s.expiring30 || 0}<small>家</small></div></div>
          <div class="kpi"><div class="kpi-label">席位使用</div>
            <div class="kpi-value">${seatTotal ? seatUsed : '不限'}<small>${seatTotal ? '/ ' + seatTotal : ''}</small></div></div>
        </div>

        <section class="card">
          <div class="card-head"><h2 class="card-title">租户列表</h2>
            <span class="card-sub">${st.loaded ? '共 ' + st.total + ' 家' : '加载中…'}</span></div>
          <div style="padding:0 16px 12px">
            <div class="filters" style="display:grid;grid-template-columns:minmax(0,1.4fr) minmax(0,1fr) minmax(0,1fr) auto;gap:10px;align-items:end">
              <div class="field"><label>关键词</label><input class="input" id="t-kw" placeholder="企业名称 / 编码 / 短码" value="${esc(st.kw)}"></div>
              <div class="field"><label>状态</label><select class="select" id="t-status">
                ${[['', '全部'], ['active', '正常'], ['pending', '未生效'], ['suspended', '已停用'], ['expired', '已到期']]
                  .map(([v, t]) => `<option value="${v}" ${v === st.status ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
              <div class="field"><label>到期</label><select class="select" id="t-expiring">
                ${[['', '全部'], ['30', '30 日内到期']].map(([v, t]) => `<option value="${v}" ${v === st.expiring ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
              <div class="field f-actions" style="margin:0"><label>&nbsp;</label>
                <div style="display:flex;gap:8px"><button class="btn btn-primary" id="t-query">查询</button>
                <button class="btn" id="t-reset">重置</button></div></div>
            </div>
          </div>
          <div class="table-wrap"><table class="tb"><thead><tr>
            <th>租户</th><th>状态</th><th>授权期</th><th>剩余</th>
            <th class="num">席位</th><th class="num">成员</th><th>租户管理员</th>
            <th style="width:250px">操作</th></tr></thead>
          <tbody>${rows.length ? rows.map(t => `<tr>
            <td><b>${esc(t.name)}</b><div class="small muted mono">${esc(t.tenantCode || t.code || '')}</div></td>
            <td>${statusTag(t.status)}</td>
            <td class="small">${esc(t.licenseStartAt || '—')} ~ ${esc(t.licenseEndAt || '长期')}</td>
            <td class="small">${remain(t.remainDays)}</td>
            <td class="num">${t.seatLimit == null ? '—' : t.seatLimit}</td>
            <td class="num">${t.members}</td>
            <td class="small">${esc(t.adminName || '—')}<div class="muted mono">${esc(t.adminPhone || '')}</div></td>
            <td class="actions">
              <button class="btn btn-sm btn-ghost" data-enter="${t.orgId}">进入租户</button>
              <button class="btn btn-sm" data-renew="${t.orgId}">续期</button>
              ${t.status === 'suspended'
                ? `<button class="btn btn-sm" data-resume="${t.orgId}">恢复</button>`
                : `<button class="btn btn-sm" data-suspend="${t.orgId}">停用</button>`}
              <button class="btn btn-sm btn-ghost" data-edit="${t.orgId}">设置</button>
            </td></tr>`).join('')
            : `<tr><td colspan="8"><div class="tb-empty">${icon('file')}<div>${st.loaded ? '没有符合条件的租户' : '加载中…'}</div></div></td></tr>`}
          </tbody></table></div>
          ${pager(st.total, st.page, st.size)}
        </section></div>`;
    },
    mount(root) {
      if (!st.loaded) load().then(() => UI.render()).catch(e => toast('err', e.message));

      $('#t-new', root).onclick = createWizard;

      const query = async () => {
        st.kw = $('#t-kw', root).value.trim();
        st.status = $('#t-status', root).value;
        st.expiring = $('#t-expiring', root).value;
        st.page = 1;
        await reload();
      };
      $('#t-query', root).onclick = query;
      $('#t-kw', root).onkeydown = (e) => { if (e.key === 'Enter') query(); };
      $('#t-reset', root).onclick = async () => {
        st.kw = ''; st.status = ''; st.expiring = ''; st.page = 1;
        await reload();
      };

      bindPager(root, async (n) => { st.page = n; await reload(); });

      const find = (id) => st.rows.find(x => String(x.orgId) === String(id));
      $$('[data-enter]', root).forEach(b => b.onclick = () => enterTenant(find(b.dataset.enter)));
      $$('[data-renew]', root).forEach(b => b.onclick = () => renewForm(find(b.dataset.renew)));
      $$('[data-edit]', root).forEach(b => b.onclick = () => editForm(find(b.dataset.edit)));
      $$('[data-suspend]', root).forEach(b => b.onclick = () => changeStatus(find(b.dataset.suspend), 'suspend'));
      $$('[data-resume]', root).forEach(b => b.onclick = () => changeStatus(find(b.dataset.resume), 'resume'));
    }
  });

  /* ==================== 平台概览 ==================== */

  const ov = { data: null, loaded: false, loading: false };

  async function loadOverview() {
    if (ov.loading) return;
    ov.loading = true;
    try {
      ov.data = await CarbonAPI.request('GET', '/platform/overview');
      ov.loaded = true;
    } catch (e) {
      toast('err', e.message);
    } finally {
      ov.loading = false;
    }
  }

  /** 续期/开通后平台侧两个数据集都要刷新。 */
  async function refreshPlatform() {
    await load();
    if (ov.loaded) await loadOverview();
    repaint();
  }

  const dayLevel = (d) => {
    if (d === null || d === undefined) return { text: '长期', color: 'var(--ink-500)', cls: 'tag-muted' };
    if (d < 0) return { text: '已过期 ' + (-d) + ' 天', color: '#B4472B', cls: 'tag-danger' };
    if (d === 0) return { text: '今天到期', color: '#B4472B', cls: 'tag-danger' };
    if (d <= 7) return { text: d + ' 天', color: '#B4472B', cls: 'tag-danger' };
    if (d <= 30) return { text: d + ' 天', color: '#B45309', cls: 'tag-warn' };
    return { text: d + ' 天', color: 'var(--ink-700)', cls: 'tag-ok' };
  };

  const shortAt = (s) => (s || '').replace('T', ' ').slice(0, 16);

  route('/platform/overview', {
    title: '平台概览', group: '平台管理',
    render() {
      const d = ov.data;
      if (!d) {
        return `<div class="page"><div class="page-head"><div><h1 class="page-title">平台概览</h1>
          <p class="page-desc">租户规模、授权到期与近期平台操作</p></div></div>
          <section class="card"><div class="card-body"><div class="tb-empty">加载中…</div></div></section></div>`;
      }
      const s = d.stats || {};
      const expiring = d.expiring || [];
      const recent = d.recent || [];
      const seatTotal = s.seatTotal == null ? 0 : s.seatTotal;
      const kpis = [
        ['租户总数', s.tenants || 0, '家', ''],
        ['正常租户', s.activeTenants || 0, '家', ''],
        ['30 日内到期', s.expiring30 || 0, '家', s.expiring30 ? 'color:#B45309' : ''],
        ['已到期', s.expiredTenants || 0, '家', s.expiredTenants ? 'color:#B4472B' : ''],
        ['席位使用', seatTotal ? (s.seatUsed || 0) : '不限', seatTotal ? '/ ' + seatTotal : '席', ''],
        ['成员账号', s.members || 0, '人', '']
      ];
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">平台概览</h1>
          <p class="page-desc">所有租户的规模、授权到期与近期平台操作；到期即阻断该租户全部账号</p></div>
          <div class="page-actions">
            <button class="btn" id="ov-tenant">租户列表</button>
            <button class="btn btn-primary" id="ov-new">${icon('plus', 15)} 开通租户</button></div></div>

        <div class="grid grid-3" style="margin-bottom:13px">
          ${kpis.map(([l, v, u, st]) => `<div class="kpi"><div class="kpi-label">${l}</div>
            <div class="kpi-value" style="${st}">${v}<small>${u}</small></div></div>`).join('')}
        </div>

        <div class="grid" style="grid-template-columns:minmax(0,1.25fr) minmax(0,1fr);gap:13px;align-items:start">
          <section class="card">
            <div class="card-head"><h2 class="card-title">授权到期预警</h2>
              <span class="card-sub">${expiring.length ? '按剩余天数升序' : '暂无'}</span></div>
            <div class="table-wrap"><table class="tb"><thead><tr>
              <th>租户</th><th>授权到期</th><th>剩余</th><th class="num">成员</th><th style="width:76px">操作</th>
            </tr></thead><tbody>${expiring.length ? expiring.map(t => {
              const lv = dayLevel(t.remainDays);
              return `<tr>
                <td><b>${esc(t.name)}</b><div class="small muted mono">${esc(t.tenantCode || t.code || '')}</div></td>
                <td class="small">${esc(t.licenseEndAt || '长期')}</td>
                <td class="small"><span style="color:${lv.color};font-weight:600">${lv.text}</span></td>
                <td class="num">${t.members}${t.seatLimit == null ? '' : ' / ' + t.seatLimit}</td>
                <td><button class="btn btn-sm" data-ov-renew="${t.orgId}">续期</button></td></tr>`;
            }).join('') : `<tr><td colspan="5"><div class="tb-empty">${icon('check')}<div>30 日内没有到期租户</div></div></td></tr>`}
            </tbody></table></div>
          </section>

          <section class="card">
            <div class="card-head"><h2 class="card-title">最近平台操作</h2>
              <span class="card-sub">开通 / 续期 / 停用 / 代管</span></div>
            <div class="card-body" style="padding-top:4px">
              ${recent.length ? `<div class="stack" style="gap:0">${recent.map((r, i) => `<div class="between"
                style="padding:9px 0${i === recent.length - 1 ? '' : ';border-bottom:1px dashed var(--line)'}">
                <div style="min-width:0">
                  <div class="small"><b>${esc(r.actionLabel || r.action)}</b>
                    <span class="muted">· ${esc(r.targetName || ('租户 #' + (r.entityId || '') + '（已删除）'))}</span></div>
                  <div class="small muted">${esc(shortAt(r.loggedAt))} · ${esc(r.actor || '系统')}</div>
                </div>
                <span class="tag tag-muted">${esc(r.entityLabel || r.entity || '')}</span></div>`).join('')}</div>`
                : `<div class="tb-empty" style="padding:26px 8px">${icon('file')}<div>暂无平台操作</div></div>`}
              <button class="btn btn-sm" id="ov-audit" style="margin-top:10px">查看全部平台审计</button>
            </div>
          </section>
        </div>

        <div class="small muted" style="margin-top:12px">
          提醒口径：到期前 30 天在概览与租户列表标黄，7 天内标红；租户管理员登录后首页顶部同步提示。到期无宽限期，续期后立即恢复。</div>
      </div>`;
    },
    mount(root) {
      if (!ov.loaded) loadOverview().then(() => UI.render()).catch(e => toast('err', e.message));
      const bind = (sel, fn) => { const el = $(sel, root); if (el) el.onclick = fn; };
      bind('#ov-new', createWizard);
      bind('#ov-tenant', () => { location.hash = '#/platform/tenants'; });
      bind('#ov-audit', () => { location.hash = '#/platform/audit'; });
      $$('[data-ov-renew]', root).forEach(b => b.onclick = () => {
        const t = ((ov.data && ov.data.expiring) || []).find(x => String(x.orgId) === String(b.dataset.ovRenew));
        renewForm(t);
      });
    }
  });

  /* ==================== 平台审计 ==================== */

  const pa = { page: 1, size: 20, total: 0, rows: [], action: '', kw: '', from: '', to: '', loaded: false, seq: 0 };

  const PA_ACTIONS = [['', '全部动作'], ['create', '开通'], ['update', '设置'], ['renew', '续期'],
    ['suspend', '停用'], ['resume', '恢复'], ['enter', '进入租户'], ['exit', '退出租户']];

  async function loadPlatformAudit() {
    const seq = ++pa.seq;
    const qs = new URLSearchParams({ page: String(pa.page), size: String(pa.size) });
    if (pa.action) qs.set('action', pa.action);
    if (pa.kw) qs.set('keyword', pa.kw);
    if (pa.from) qs.set('from', pa.from);
    if (pa.to) qs.set('to', pa.to);
    try {
      const r = await CarbonAPI.request('GET', '/platform/audit?' + qs.toString());
      if (seq !== pa.seq) return;
      pa.rows = r.rows || [];
      pa.total = r.total || 0;
      pa.loaded = true;
      if (pa.page > 1 && !pa.rows.length && pa.total > 0) { pa.page -= 1; return loadPlatformAudit(); }
    } catch (e) { toast('err', e.message); }
  }

  function changeSummary(before, after, action) {
    const parse = (s) => { try { return s ? JSON.parse(s) : null; } catch (e) { return null; } };
    const b = parse(before), a = parse(after);
    const kb = b ? Object.keys(b) : [];
    const ka = a ? Object.keys(a) : [];
    if (!kb.length && !ka.length) return '—';
    if (!kb.length) return '建立 ' + ka.length + ' 个字段';
    if (!ka.length) return ((action === 'delete') ? '删除' : '变更前') + '：' + kb.slice(0, 3).join('、') + (kb.length > 3 ? '…' : '');
    const keys = [...new Set([...kb, ...ka])].filter(k => JSON.stringify(b[k]) !== JSON.stringify(a[k]));
    return keys.length ? keys.length + ' 项变更：' + keys.slice(0, 4).join('、') + (keys.length > 4 ? '…' : '') : '无字段变化';
  }

  function pretty(s) {
    try { return JSON.stringify(JSON.parse(s), null, 2); } catch (e) { return s || ''; }
  }

  route('/platform/audit', {
    title: '平台审计', group: '平台管理',
    render() {
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">平台审计</h1>
          <p class="page-desc">跨租户的平台侧操作留痕：开通、设置、续期、停用、恢复与代管进入</p></div></div>

        <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
          <div class="field"><label>动作</label><select class="select" id="pa-action">
            ${PA_ACTIONS.map(([v, t]) => `<option value="${v}" ${v === pa.action ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
          <div class="field"><label>关键词</label><input class="input" id="pa-kw" placeholder="租户名称 / 编码 / 操作人" value="${esc(pa.kw)}"></div>
          <div class="field" style="min-width:236px" data-parange><label>操作日期</label>
            ${UI.rangePicker({ mode: 'date', from: pa.from, to: pa.to, ph: '开始日期 ~ 结束日期' })}</div>
          <div class="field"><label>&nbsp;</label><div class="toolbar">
            <button class="btn btn-primary" id="pa-search">查询</button>
            <button class="btn" id="pa-reset">重置</button></div></div>
        </div></div></section>

        <section class="card">
          <div class="card-head"><h2 class="card-title">操作记录</h2>
            <span class="card-sub">${pa.loaded ? '共 ' + pa.total + ' 条' : '加载中…'}</span></div>
          <div class="table-wrap"><table class="tb"><thead><tr>
            <th style="width:148px">时间</th><th>操作人</th><th>动作</th><th>目标租户</th>
            <th>变更摘要</th><th style="width:60px"></th></tr></thead>
          <tbody>${pa.rows.length ? pa.rows.map(r => `<tr>
              <td class="small">${esc(shortAt(r.loggedAt))}</td>
              <td class="small mono">${esc(r.actor || '—')}</td>
              <td><span class="tag tag-info">${esc(r.actionLabel || r.action)}</span></td>
              <td class="small">${esc(r.targetName || ('租户 ' + (r.entityId || '—')))}</td>
              <td class="small muted">${esc(changeSummary(r.beforeJson, r.afterJson, r.action))}</td>
              <td><button class="btn btn-sm btn-ghost" data-pa-open="${r.id}">详情</button></td>
            </tr>`).join('')
            : `<tr><td colspan="6"><div class="tb-empty">${icon('file')}<div>${pa.loaded ? '没有符合条件的平台操作' : '加载中…'}</div></div></td></tr>`}
          </tbody></table></div>
          ${pager(pa.total, pa.page, pa.size)}
        </section></div>`;
    },
    mount(root) {
      if (!pa.loaded) loadPlatformAudit().then(() => UI.render());
      UI.bindRangePickers(root);
      const readRange = () => {
        const box = root.querySelector('[data-parange] [data-rp]');
        pa.from = box ? box.dataset.from || '' : '';
        pa.to = box ? box.dataset.to || '' : '';
      };
      $('#pa-search', root).onclick = async () => {
        pa.action = $('#pa-action', root).value;
        pa.kw = $('#pa-kw', root).value.trim();
        readRange();
        pa.page = 1;
        await loadPlatformAudit();
        UI.render();
      };
      $('#pa-kw', root).onkeydown = (e) => { if (e.key === 'Enter') $('#pa-search', root).click(); };
      $('#pa-reset', root).onclick = async () => {
        pa.action = ''; pa.kw = ''; pa.from = ''; pa.to = ''; pa.page = 1;
        await loadPlatformAudit();
        UI.render();
      };
      bindPager(root, async (n) => { pa.page = n; await loadPlatformAudit(); UI.render(); });
      $$('[data-pa-open]', root).forEach(b => b.onclick = () => {
        const r = pa.rows.find(x => String(x.id) === String(b.dataset.paOpen));
        if (!r) return;
        modal({
          title: (r.actionLabel || r.action) + ' · ' + (r.targetName || ('租户 ' + r.entityId)), width: 720,
          body: `<div class="grid grid-2" style="gap:0 16px;margin-bottom:10px">
            <div class="between" style="padding:6px 0;border-bottom:1px dashed var(--line)">
              <span class="muted small">操作人</span><b class="mono">${esc(r.actor || '—')}</b></div>
            <div class="between" style="padding:6px 0;border-bottom:1px dashed var(--line)">
              <span class="muted small">时间</span><b class="small">${esc(shortAt(r.loggedAt))}</b></div>
          </div>
          ${[['变更前', r.beforeJson], ['变更后', r.afterJson]].map(([t, v]) => `<div style="margin-top:8px">
            <div class="small muted" style="margin-bottom:4px">${t}</div>
            <pre class="mono" style="background:#F7FAF8;border:1px solid var(--line);border-radius:8px;padding:10px;max-height:200px;overflow:auto;font-size:12px;margin:0">${esc(pretty(v) || '—')}</pre>
          </div>`).join('')}`,
          foot: '<button class="btn btn-primary" data-close>关闭</button>'
        });
      });
    }
  });
})();
