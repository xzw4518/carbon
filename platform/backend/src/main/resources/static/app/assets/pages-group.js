/* 集团—工厂汇总：当前账号可见企业的横向对比 */
(function () {
  'use strict';
  const { esc, icon, toast, route } = UI;
  const st = { data: null, year: new Date().getFullYear(), loading: false };

  function load() {
    st.loading = true;
    return CarbonAPI.request('GET', '/group/summary?year=' + st.year)
      .then(d => { st.data = d; })
      .catch(e => toast('err', e.message))
      .finally(() => { st.loading = false; });
  }

  route('/group/overview', {
    title: '集团汇总', group: '企业管理',
    render() {
      const d = st.data || { orgs: [] };
      const orgs = d.orgs || [];
      const maxE = Math.max(1, ...orgs.map(o => Number(o.emission) || 0));
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">集团汇总</h1>
          <p class="page-desc">按企业横向对比排放与能耗；集团账号可看到下属工厂</p></div>
          <div class="page-actions"><div class="field" style="margin:0"><label>年度</label>
            <input class="input" id="g-year" type="number" value="${st.year}" style="width:110px"></div></div></div>
        <div class="grid grid-4" style="margin-bottom:13px">
          <div class="kpi"><div class="kpi-label">企业数</div><div class="kpi-value">${d.orgCount || orgs.length}<small>家</small></div></div>
          <div class="kpi"><div class="kpi-label">排放合计</div><div class="kpi-value">${d.totalEmission == null ? '—' : Number(d.totalEmission).toFixed(1)}<small>tCO₂e</small></div></div>
          <div class="kpi"><div class="kpi-label">能耗合计</div><div class="kpi-value">${d.totalEnergy == null ? '—' : Number(d.totalEnergy).toFixed(0)}<small>单位</small></div></div>
          <div class="kpi"><div class="kpi-label">口径</div><div class="kpi-value" style="font-size:16px">${st.year} 年</div>
            <div class="kpi-foot">按核算期间汇总</div></div>
        </div>
        ${orgs.length <= 1 ? `<div style="border:1px solid #D6E4DE;background:#F7FBF9;color:#0F4C3A;border-radius:10px;padding:10px 12px;font-size:12.5px;margin-bottom:12px">
          当前账号只属于 1 家企业。如需集团视图，请让平台在「租户管理」里把工厂挂到集团下，并把你加入多家企业。</div>` : ''}
        <section class="card" style="margin-bottom:13px"><div class="card-head"><h2 class="card-title">排放对比</h2>
          <span class="card-sub">tCO₂e</span></div><div class="card-body">
          ${orgs.length ? orgs.map(o => `<div style="margin-bottom:9px">
            <div class="between small"><span>${esc(o.name)}</span><b class="mono">${Number(o.emission || 0).toFixed(1)}</b></div>
            <div style="height:8px;background:#EEF3F1;border-radius:6px;overflow:hidden;margin-top:4px">
              <i style="display:block;height:100%;width:${Math.round((Number(o.emission) || 0) / maxE * 100)}%;background:#0F7A5A"></i></div>
          </div>`).join('') : `<div class="tb-empty">${icon('chart')}<div>暂无数据</div></div>`}
        </div></section>
        <section class="card"><div class="card-head"><h2 class="card-title">企业明细</h2>
          <span class="card-sub">${orgs.length} 家</span></div>
          <div class="table-wrap"><table class="tb"><thead><tr>
            <th>企业</th><th class="num">层级</th><th>状态</th><th class="num">排放(tCO₂e)</th>
            <th class="num">能耗</th><th class="num">设备</th><th class="num">成员</th></tr></thead>
          <tbody>${orgs.length ? orgs.map(o => `<tr>
            <td><b>${esc(o.name)}</b>${o.parentId ? '<div class="small muted">隶属集团 ' + o.parentId + '</div>' : ''}</td>
            <td class="num">${o.level}</td>
            <td>${o.status === 'active' ? '<span class="tag tag-ok dot">正常</span>' : `<span class="tag tag-muted">${esc(o.status)}</span>`}</td>
            <td class="num">${Number(o.emission || 0).toFixed(1)}</td>
            <td class="num">${Number(o.energy || 0).toFixed(0)}</td>
            <td class="num">${o.devices}</td>
            <td class="num">${o.members}</td></tr>`).join('')
            : '<tr><td colspan="7"><div class="tb-empty">暂无企业</div></td></tr>'}</tbody></table></div>
        </section></div>`;
    },
    mount(root) {
      if (!st.data && !st.loading) load().then(() => UI.render());
      const y = root.querySelector('#g-year');
      if (y) y.onchange = () => { st.year = Number(y.value) || st.year; st.data = null; load().then(() => UI.render()); };
    }
  });
})();
