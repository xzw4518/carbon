/* 系统管理 · 账号管理：成员列表、新增、改角色、重置密码、启停、移出、导入导出 */
(function () {
  'use strict';
  const { $, esc, icon, toast, modal, confirm, busy, route } = UI;

  const STATUS = [['active', '正常'], ['disabled', '停用'], ['pending', '待激活']];
  const statusTag = (s) => s === 'active'
    ? '<span class="tag tag-ok dot">正常</span>'
    : s === 'disabled'
      ? '<span class="tag tag-muted dot">停用</span>'
      : '<span class="tag tag-warn dot">待激活</span>';

  const st = { rows: [], roles: [], kw: '', userType: '', roleId: '', status: '', loaded: false };
  const canManage = () => CarbonAPI.me() && CarbonAPI.me().role === 'admin';

  async function load() {
    const [rows, roles] = await Promise.all([
      CarbonAPI.request('GET', '/users'),
      CarbonAPI.request('GET', '/roles')
    ]);
    st.rows = rows;
    st.roles = roles || [];
    st.loaded = true;
  }

  const roleOptions = (selected) => st.roles
    .map(r => `<option value="${r.roleId}" ${String(r.roleId) === String(selected) ? 'selected' : ''}>${esc(r.name)}</option>`)
    .join('');

  function filtered() {
    const kw = st.kw.trim();
    return st.rows.filter(r => {
      if (st.userType && r.userType !== st.userType) return false;
      if (st.roleId && String(r.roleId) !== String(st.roleId)) return false;
      if (st.status && r.status !== st.status) return false;
      if (kw && !(r.phone || '').includes(kw) && !(r.displayName || '').includes(kw)) return false;
      return true;
    });
  }

  function table() {
    const rows = filtered();
    if (!rows.length) {
      return `<div class="tb-empty">${icon('user')}<div>还没有成员</div>
        <div class="small">点右上角「新增成员」，用手机号开通账号</div></div>`;
    }
    const editable = canManage();
    return `<div class="table-wrap"><table class="tb"><thead><tr>
        <th>成员</th><th>手机号</th><th>用户类型</th><th>角色</th><th>状态</th>
        <th>最近登录</th><th style="width:260px">操作</th></tr></thead>
      <tbody>${rows.map(r => `<tr>
        <td><b>${esc(r.displayName || '—')}</b>${r.mustChangePassword ? ' <span class="tag tag-warn">待改密</span>' : ''}</td>
        <td>${esc(r.phone || '—')}</td>
        <td>${r.userType === 'tenant_admin'
          ? '<span class="tag tag-info">租户管理员</span>'
          : '普通用户'}</td>
        <td>${esc(r.userType === 'tenant_admin' ? '—' : (r.roleName || '—'))}</td>
        <td>${statusTag(r.status)}</td>
        <td>${esc(r.lastLoginAt || '从未登录')}</td>
        <td class="actions">${editable ? `
          <button class="btn btn-sm btn-ghost" data-act="edit" data-id="${r.membershipId}">编辑</button>
          <button class="btn btn-sm" data-act="reset" data-id="${r.membershipId}">重置密码</button>
          <button class="btn btn-sm" data-act="${r.status === 'disabled' ? 'enable' : 'disable'}" data-id="${r.membershipId}">${r.status === 'disabled' ? '启用' : '停用'}</button>
          <button class="btn btn-sm" data-act="logs" data-id="${r.membershipId}">日志</button>
          <button class="btn btn-sm" data-act="handover" data-id="${r.membershipId}">交接</button>
          <button class="btn btn-sm" data-act="remove" data-id="${r.membershipId}">移出</button>` : '<span class="small muted">只读</span>'}
        </td></tr>`).join('')}</tbody></table></div>`;
  }

  function copyBox(label, value) {
    return `<div style="background:#F0FBF5;border:1px solid #BFE6CB;border-radius:10px;padding:12px">
      <div class="small muted">${esc(label)}（仅显示一次，请立即保存）</div>
      <div class="row" style="gap:8px;margin-top:6px">
        <input class="input mono" id="tmp-val" value="${esc(value)}" readonly style="flex:1">
        <button class="btn btn-sm" id="tmp-copy">复制</button></div></div>`;
  }

  function bindCopy(wrap) {
    const btn = wrap.querySelector('#tmp-copy');
    if (!btn) return;
    btn.onclick = async () => {
      const v = wrap.querySelector('#tmp-val');
      try { await navigator.clipboard.writeText(v.value); toast('ok', '已复制'); }
      catch (e) { v.select(); document.execCommand('copy'); toast('ok', '已复制'); }
    };
  }

  function newForm() {
    modal({
      title: '新增成员', width: 520,
      body: `<form id="u-form">
        <div class="field"><label class="req">手机号</label><input class="input" name="phone" maxlength="11" placeholder="11 位手机号，作为登录账号"></div>
        <div class="grid grid-2" style="margin-top:10px">
          <div class="field"><label class="req">姓名</label><input class="input" name="displayName"></div>
          <div class="field"><label class="req">用户类型</label><select class="select" name="userType" id="u-form-type">
            <option value="user">普通用户</option><option value="tenant_admin">租户管理员</option></select></div>
          <div class="field" id="u-form-role-box"><label class="req">角色</label><select class="select" name="roleId">
            ${roleOptions('')}</select></div>
        </div>
        <div class="grid grid-2" style="margin-top:10px">
          <div class="field"><label>工号</label><input class="input" name="employeeNo" placeholder="选填"></div>
          <div class="field"><label>职位</label><input class="input" name="title" placeholder="选填"></div>
        </div>
        <div class="small muted" style="margin-top:10px">系统会生成一次性临时密码，成员首次登录后必须修改。</div>
      </form>`,
      foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="u-save">创建</button>`,
      onMount(wrap, close) {
        const typeSel = wrap.querySelector('#u-form-type');
        const roleBox = wrap.querySelector('#u-form-role-box');
        typeSel.onchange = () => { roleBox.style.display = typeSel.value === 'tenant_admin' ? 'none' : ''; };
        wrap.querySelector('#u-save').onclick = async (e) => {
          const d = Object.fromEntries(new FormData(wrap.querySelector('#u-form')));
          if (!/^1\d{10}$/.test(d.phone || '')) { toast('err', '手机号格式不正确'); return; }
          if (!d.displayName) { toast('err', '请填写姓名'); return; }
          const done = busy(e.currentTarget, '创建中');
          try {
            const r = await API_CREATE(d);
            done(); close();
            if (r.tempPassword) {
              modal({
                title: '账号已创建', width: 480,
                body: `<div class="small" style="margin-bottom:10px">成员 <b>${esc(d.displayName)}</b>（${esc(d.phone)}）已加入本企业。</div>
                  ${copyBox('临时密码', r.tempPassword)}
                  <div class="small muted" style="margin-top:10px">7 天内未登录将自动停用；登录后必须改密。</div>`,
                foot: `<button class="btn btn-primary" data-close>我已保存</button>`,
                onMount(w) { bindCopy(w); }
              });
            } else {
              toast('ok', '成员已加入', '该手机号已有账号，无需新密码');
            }
            await load(); UI.render();
          } catch (err) { done(); toast('err', err.message); }
        };
      }
    });
  }

  async function API_CREATE(d) {
    return CarbonAPI.request('POST', '/users', {
      phone: d.phone, displayName: d.displayName,
      userType: d.userType || 'user',
      roleId: d.userType === 'tenant_admin' ? null : Number(d.roleId),
      employeeNo: d.employeeNo || null, title: d.title || null
    });
  }

  function editForm(r) {
    modal({
      title: '编辑成员', width: 500,
      body: `<form id="u-edit"><div class="grid grid-2">
        <div class="field"><label>姓名</label><input class="input" name="displayName" value="${esc(r.displayName || '')}"></div>
        <div class="field"><label>用户类型</label><select class="select" name="userType" id="u-edit-type">
          <option value="user" ${r.userType !== 'tenant_admin' ? 'selected' : ''}>普通用户</option>
          <option value="tenant_admin" ${r.userType === 'tenant_admin' ? 'selected' : ''}>租户管理员</option></select></div>
        <div class="field" id="u-edit-role-box"><label>角色</label><select class="select" name="roleId">
          ${roleOptions(r.roleId)}</select></div>
        <div class="field"><label>工号</label><input class="input" name="employeeNo" value="${esc(r.employeeNo || '')}"></div>
        <div class="field"><label>职位</label><input class="input" name="title" value="${esc(r.title || '')}"></div>
        <div class="field" style="grid-column:1/-1"><label>邮箱</label><input class="input" name="email" value="${esc(r.email || '')}"></div>
      </div>
      <div class="small muted" style="margin-top:10px">修改角色后该成员需要重新登录。</div></form>`,
      foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="u-edit-save">保存</button>`,
      onMount(wrap, close) {
        const typeSel = wrap.querySelector('#u-edit-type');
        const roleBox = wrap.querySelector('#u-edit-role-box');
        const sync = () => { roleBox.style.display = typeSel.value === 'tenant_admin' ? 'none' : ''; };
        typeSel.onchange = sync; sync();
        wrap.querySelector('#u-edit-save').onclick = async (e) => {
          const d = Object.fromEntries(new FormData(wrap.querySelector('#u-edit')));
          const done = busy(e.currentTarget, '保存中');
          try {
            await CarbonAPI.request('PUT', '/users/' + r.membershipId, {
              displayName: d.displayName, userType: d.userType,
              roleId: d.userType === 'tenant_admin' ? null : Number(d.roleId),
              employeeNo: d.employeeNo || null, title: d.title || null,
              email: d.email || null,
              version: r.version
            });
            done(); close(); toast('ok', '成员已更新');
            await load(); UI.render();
          } catch (err) { done(); toast('err', err.message); }
        };
      }
    });
  }

  function logsModal(r) {
    CarbonAPI.request('GET', '/users/' + r.membershipId + '/login-logs?limit=20').then(list => {
      modal({
        title: '登录日志 · ' + (r.displayName || r.phone), width: 620,
        body: list.length ? `<div class="table-wrap"><table class="tb"><thead><tr>
            <th>时间</th><th>手机号</th><th>方式</th><th>结果</th><th>IP</th></tr></thead>
          <tbody>${list.map(x => `<tr><td>${esc(x.at || '')}</td><td>${esc(x.phone || '')}</td>
            <td>${x.channel === 'sms' ? '短信' : '密码'}</td>
            <td>${x.result === 'ok' ? '<span class="tag tag-ok dot">成功</span>' : `<span class="tag tag-warn">${esc(x.result)}</span>`}</td>
            <td>${esc(x.ip || '—')}</td></tr>`).join('')}</tbody></table></div>`
          : '<div class="tb-empty">没有登录记录</div>',
        foot: `<button class="btn" data-close>关闭</button>`
      });
    }).catch(e => toast('err', e.message));
  }

  /** 模板兜底：后端接口未部署时在前端生成同一份 CSV，保证导入可用。 */
  const TEMPLATE_CSV = '\uFEFF手机号,姓名,用户类型,角色,工号,职位\r\n'
    + '13900000001,张三,普通用户,运营,EMP001,能源管理员\r\n'
    + '13900000002,李四,普通用户,,EMP002,碳核算专员\r\n';
  function downloadTemplateLocal() {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([TEMPLATE_CSV], { type: 'text/csv;charset=utf-8' }));
    a.download = '成员导入模板.csv';
    a.click();
    URL.revokeObjectURL(a.href);
  }

  function importModal() {
    modal({
      title: '导入成员数据', width: 540,
      body: `<div class="stack">
        <button class="btn" id="iu-tpl" style="justify-content:center">${icon('download', 16)} 下载模板</button>
        <div class="small muted">CSV 文件（UTF-8）</div>
        <label class="btn" style="justify-content:center;height:auto;padding:10px 12px;cursor:pointer">
          ${icon('upload', 16)} <span id="iu-name">上传 CSV 文件</span>
          <input type="file" id="iu-file" accept=".csv" style="display:none"></label>
        <div class="small muted">列顺序：手机号,姓名,用户类型,角色,工号,职位</div>
        <div class="hr" style="margin:8px 0"></div>
        <div class="small" style="font-weight:600">导入说明：</div>
        <ol class="small muted" style="padding-left:18px;margin:4px 0">
          <li>请先下载标准模板，按照模板格式填写数据</li>
          <li>用户类型填「普通用户」或「租户管理员」，留空按普通用户；角色只能填「角色管理」中已配置的角色，可留空</li>
          <li>只能上传一个 CSV 文件；删除已上传文件可重新上传</li></ol>
        <div class="row small hide" id="iu-del-wrap" style="gap:8px"><span class="muted" id="iu-file-name"></span>
          <button class="btn btn-sm btn-ghost" id="iu-del">删除文件</button></div>
        <div id="iu-result"></div></div>`,
      foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="iu-run">开始导入</button>`,
      onMount(wrap, close) {
        const f = wrap.querySelector('#iu-file'), name = wrap.querySelector('#iu-name');
        const wrapDel = wrap.querySelector('#iu-del-wrap'), fileName = wrap.querySelector('#iu-file-name');
        f.onchange = () => {
          name.textContent = f.files[0] ? '已选择文件' : '上传 CSV 文件';
          wrapDel.classList.toggle('hide', !f.files[0]);
          fileName.textContent = f.files[0] ? f.files[0].name : '';
        };
        wrap.querySelector('#iu-del').onclick = () => {
          f.value = ''; name.textContent = '上传 CSV 文件'; wrapDel.classList.add('hide');
        };
        wrap.querySelector('#iu-tpl').onclick = async (e) => {
          const done = busy(e.currentTarget, '生成中');
          let ok = false;
          try { ok = await CarbonAPI.download('/users/import-template', true); } catch (err) { ok = false; }
          done();
          if (!ok) downloadTemplateLocal();
          toast('ok', '模板已下载');
        };
        wrap.querySelector('#iu-run').onclick = async (e) => {
          if (!f.files[0]) { toast('err', '请先上传 CSV 文件'); return; }
          const done = busy(e.currentTarget, '导入中');
          try {
            const fd = new FormData(); fd.append('file', f.files[0]);
            const r = await CarbonAPI.request('POST', '/users/import', fd);
            done();
            const errs = (r.errors || []).map(x => `<div class="small">第 ${x.line} 行 ${esc(x.phone || '')}：${esc(x.message)}</div>`).join('');
            wrap.querySelector('#iu-result').innerHTML =
              `<div class="small" style="margin-top:8px">成功 ${r.success} 条，失败 ${r.failed} 条</div>${errs}
               ${r.tempPassword ? copyBox('本批临时密码', r.tempPassword) : ''}`;
            bindCopy(wrap);
            await load(); UI.render();
          } catch (err) { done(); toast('err', err.message); }
        };
      }
    });
  }

  route('/system/users', {
    title: '账号管理', group: '系统管理',
    render() {
      const rows = filtered();
      const dis = canManage() ? '' : ' disabled';
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">账号管理</h1>
          <p class="page-desc">手机号即账号；用户类型决定层级，角色决定能进入哪些页面</p></div>
          <div class="page-actions">
            ${canManage() ? `<button class="btn" id="u-import">${icon('upload', 15)} 导入</button>
            <button class="btn" id="u-export">${icon('download', 15)} 导出</button>
            <button class="btn btn-primary" id="u-new">${icon('plus', 15)} 新增成员</button>` : ''}
          </div></div>
        <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
          <div class="field"><label>关键词</label><input class="input" id="u-kw" value="${esc(st.kw)}" placeholder="手机号或姓名"></div>
          <div class="field"><label>用户类型</label><select class="select" id="u-type">
            <option value="">全部</option>
            <option value="tenant_admin" ${st.userType === 'tenant_admin' ? 'selected' : ''}>租户管理员</option>
            <option value="user" ${st.userType === 'user' ? 'selected' : ''}>普通用户</option></select></div>
          <div class="field"><label>角色</label><select class="select" id="u-role">
            <option value="">全部</option>${roleOptions(st.roleId)}</select></div>
          <div class="field"><label>状态</label><select class="select" id="u-status">
            <option value="">全部</option>${STATUS.map(([v, t]) => `<option value="${v}" ${st.status === v ? 'selected' : ''}>${t}</option>`).join('')}</select></div>
          <div class="field"><label>&nbsp;</label><div class="toolbar">
            <button class="btn btn-primary" id="u-query">查询</button>
            <button class="btn" id="u-reset">重置</button></div></div>
        </div></div></section>
        <section class="card"><div class="card-head"><h2 class="card-title">成员列表</h2>
          <span class="card-sub">${rows.length} 人</span></div>${table()}</section>
      </div>`;
    },
    mount(root) {
      if (!st.loaded) load().then(() => UI.render()).catch(e => toast('err', e.message));
      const q = () => {
        st.kw = root.querySelector('#u-kw').value;
        st.userType = root.querySelector('#u-type').value;
        st.roleId = root.querySelector('#u-role').value;
        st.status = root.querySelector('#u-status').value;
        UI.render();
      };
      const btnQ = root.querySelector('#u-query');
      if (btnQ) btnQ.onclick = q;
      const btnR = root.querySelector('#u-reset');
      if (btnR) btnR.onclick = () => { st.kw = ''; st.userType = ''; st.roleId = ''; st.status = ''; UI.render(); };
      const kw = root.querySelector('#u-kw');
      if (kw) kw.onkeydown = e => { if (e.key === 'Enter') q(); };

      const btnNew = root.querySelector('#u-new');
      if (btnNew) btnNew.onclick = newForm;
      const btnImp = root.querySelector('#u-import');
      if (btnImp) btnImp.onclick = importModal;
      const btnExp = root.querySelector('#u-export');
      if (btnExp) btnExp.onclick = async (e) => {
        const done = busy(e.currentTarget, '导出中');
        try { await CarbonAPI.download('/users/export'); done(); }
        catch (err) { done(); toast('err', err.message); }
      };

      root.querySelectorAll('[data-act]').forEach(b => b.onclick = async () => {
        const r = st.rows.find(x => String(x.membershipId) === b.dataset.id);
        if (!r) return;
        const act = b.dataset.act;
        if (act === 'edit') { editForm(r); return; }
        if (act === 'logs') { logsModal(r); return; }
        if (act === 'handover') {
          const others = st.rows.filter(x => x.membershipId !== r.membershipId && x.status === 'active');
          if (!others.length) { toast('warn', '没有其他可接手的成员', '请先新增接手人'); return; }
          modal({
            title: '数据交接 · ' + (r.displayName || r.phone), width: 520,
            body: `<div class="small muted" style="margin-bottom:10px">把该成员在本企业创建的业务数据转给接手人，操作记入审计。</div>
              <div class="field"><label class="req">接手人</label><select class="select" id="ho-to">
                ${others.map(o => `<option value="${o.membershipId}">${esc(o.displayName || o.phone)} · ${esc(o.roleLabel)}</option>`).join('')}
              </select></div>`,
            foot: `<button class="btn" data-close>取消</button><button class="btn btn-primary" id="ho-ok">确认交接</button>`,
            onMount(wrap, close) {
              wrap.querySelector('#ho-ok').onclick = async (e) => {
                const done = busy(e.currentTarget, '交接中');
                try {
                  const res = await CarbonAPI.request('POST', '/users/' + r.membershipId + '/handover',
                    { toMembershipId: Number(wrap.querySelector('#ho-to').value) });
                  done(); close();
                  toast('ok', '交接完成', '共转移 ' + res.rows + ' 条记录');
                  await load(); UI.render();
                } catch (err) { done(); toast('err', err.message); }
              };
            }
          });
          return;
        }
        if (act === 'reset') {
          const ok = await confirm({ title: '重置密码', content: `将为「${r.displayName || r.phone}」生成新的临时密码，旧密码立即失效。`, okText: '重置' });
          if (!ok) return;
          try {
            const res = await CarbonAPI.request('POST', '/users/' + r.membershipId + '/reset-password');
            modal({
              title: '临时密码已生成', width: 460,
              body: copyBox('临时密码', res.tempPassword) +
                '<div class="small muted" style="margin-top:10px">7 天内有效，登录后必须修改。</div>',
              foot: `<button class="btn btn-primary" data-close>我已保存</button>`,
              onMount(w) { bindCopy(w); }
            });
            await load(); UI.render();
          } catch (err) { toast('err', err.message); }
          return;
        }
        if (act === 'disable' || act === 'enable') {
          const disable = act === 'disable';
          const ok = await confirm({
            title: disable ? '停用成员' : '启用成员',
            content: disable ? `停用后「${r.displayName || r.phone}」将无法登录本企业，历史数据保留。` : `确认恢复「${r.displayName || r.phone}」的访问？`,
            okText: disable ? '停用' : '启用', danger: disable
          });
          if (!ok) return;
          try {
            await CarbonAPI.request('POST', '/users/' + r.membershipId + '/' + act);
            toast('ok', disable ? '已停用' : '已启用');
            await load(); UI.render();
          } catch (err) { toast('err', err.message); }
          return;
        }
        if (act === 'remove') {
          const ok = await confirm({ title: '移出成员', content: `将「${r.displayName || r.phone}」移出本企业，其创建的数据仍保留，建议先做交接。`, okText: '移出', danger: true });
          if (!ok) return;
          try {
            await CarbonAPI.request('DELETE', '/users/' + r.membershipId);
            toast('ok', '已移出');
            await load(); UI.render();
          } catch (err) { toast('err', err.message); }
        }
      });
    }
  });

  /* ---------------- 租户信息（授权期只读） ---------------- */

  const lic = { data: null, loading: false };

  async function loadLicense() {
    if (lic.loading) return;
    lic.loading = true;
    try {
      lic.data = await CarbonAPI.request('GET', '/org/license');
    } catch (e) {
      toast('err', e.message);
    } finally {
      lic.loading = false;
    }
  }

  /* ---------------- 角色管理（页面级） ---------------- */

  const rl = { rows: [], catalog: null, loading: false };

  async function loadRoles() {
    if (rl.loading) return;
    rl.loading = true;
    try {
      const [rows, catalog] = await Promise.all([
        CarbonAPI.request('GET', '/roles'),
        CarbonAPI.request('GET', '/roles/pages')
      ]);
      rl.rows = rows || [];
      rl.catalog = catalog;
    } catch (e) {
      toast('err', e.message);
    } finally {
      rl.loading = false;
    }
  }

  function pageGroups(selected) {
    const sel = selected || [];
    return (rl.catalog.groups || []).map(g => {
      const all = g.pages.every(pg => sel.indexOf(pg.code) >= 0);
      return `<div class="page-group" data-group-box="${esc(g.name)}">
        <label class="page-group-head"><input type="checkbox" data-group="${esc(g.name)}" ${all ? 'checked' : ''}>
          <b>${esc(g.name)}</b><span>${g.pages.length} 个页面</span></label>
        <div class="page-items">
          ${g.pages.map(pg => `<label class="page-item"><input type="checkbox" data-page="${pg.code}"
            ${sel.indexOf(pg.code) >= 0 ? 'checked' : ''}> ${esc(pg.name)}</label>`).join('')}
        </div></div>`;
    }).join('');
  }

  function roleEditor(role) {
    const editing = !!role;
    const selected = (role && role.pages) || ['home'];
    modal({
      title: editing ? '编辑角色 · ' + role.name : '新建角色', width: 860,
      body: `<div class="grid grid-2" style="margin-bottom:14px">
          <div class="field"><label class="req">角色名称</label>
            <input class="input" id="role-name" maxlength="32" value="${esc(editing ? role.name : '')}" placeholder="如 财务查看"></div>
          <div class="field"><label>说明</label>
            <input class="input" id="role-desc" value="${esc(editing ? (role.remark || '') : '')}" placeholder="这个角色解决什么问题"></div>
        </div>
        <div class="between" style="margin-bottom:8px">
          <div class="small muted">勾选这个角色可以进入的功能页面；系统管理不在此列</div>
          <div class="small"><b id="page-count">${selected.length}</b> / ${rl.catalog.total}</div>
        </div>
        <div class="page-tree" id="page-tree">${pageGroups(selected)}</div>`,
      foot: '<button class="btn" data-close>取消</button><button class="btn btn-primary" id="role-save">保存角色</button>',
      onMount(wrap, close) {
        const countEl = wrap.querySelector('#page-count');
        const refresh = () => {
          const n = wrap.querySelectorAll('input[data-page]:checked').length;
          countEl.textContent = n;
          wrap.querySelectorAll('.page-group').forEach(box => {
            const boxes = [...box.querySelectorAll('input[data-page]')];
            const g = box.querySelector('input[data-group]');
            const checked = boxes.filter(b => b.checked).length;
            g.checked = checked === boxes.length;
            g.indeterminate = checked > 0 && checked < boxes.length;
          });
        };
        wrap.querySelectorAll('input[data-group]').forEach(g => g.onchange = () => {
          const box = g.closest('.page-group');
          box.querySelectorAll('input[data-page]').forEach(pg => { pg.checked = g.checked; });
          refresh();
        });
        wrap.querySelectorAll('input[data-page]').forEach(pg => pg.onchange = refresh);

        wrap.querySelector('#role-save').onclick = async (e) => {
          const name = wrap.querySelector('#role-name').value.trim();
          const remark = wrap.querySelector('#role-desc').value.trim();
          const pages = [...wrap.querySelectorAll('input[data-page]:checked')].map(x => x.dataset.page);
          if (!name) { toast('err', '请填写角色名称'); return; }
          if (!pages.length) { toast('err', '至少勾选一个页面'); return; }
          const done = busy(e.currentTarget, '保存中');
          try {
            if (editing) {
              await CarbonAPI.request('PUT', '/roles/' + role.roleId, { name, remark, pages });
              toast('ok', '角色已保存', '该角色下的用户需要重新登录');
            } else {
              await CarbonAPI.request('POST', '/roles', { name, remark, pages });
              toast('ok', '角色已创建', name + ' · 可进入 ' + pages.length + ' 个页面');
            }
            done(); close();
            await loadRoles(); UI.render();
          } catch (err) { done(); toast('err', err.message); }
        };
        refresh();
      }
    });
  }

  route('/system/roles', {
    title: '角色管理', group: '系统管理',
    render() {
      if (!rl.catalog) {
        return `<div class="page"><div class="page-head"><div><h1 class="page-title">角色管理</h1>
          <p class="page-desc">角色只配置可进入的功能页面</p></div></div>
          <section class="card"><div class="card-body"><div class="tb-empty">加载中…</div></div></section></div>`;
      }
      const rows = rl.rows;
      const users = rows.reduce((n, r) => n + (r.users || 0), 0);
      const defaults = rows.filter(r => r.defaultRole).length;
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">角色管理</h1>
          <p class="page-desc">角色只配置可进入的功能页面；系统管理不在页面目录中</p></div>
          <div class="page-actions"><button class="btn btn-primary" id="r-new">${icon('plus', 15)} 新建角色</button></div></div>

        <div class="grid grid-4" style="margin-bottom:13px">
          <div class="kpi"><div class="kpi-label">角色总数</div><div class="kpi-value">${rows.length}<small>/ 30</small></div></div>
          <div class="kpi"><div class="kpi-label">已分配用户</div><div class="kpi-value">${users}<small>人</small></div></div>
          <div class="kpi"><div class="kpi-label">默认角色</div><div class="kpi-value">${defaults}<small>个</small></div></div>
          <div class="kpi"><div class="kpi-label">可配置页面</div><div class="kpi-value">${rl.catalog.total}<small>个</small></div></div>
        </div>

        <section class="card">
          <div class="card-head"><h2 class="card-title">角色列表</h2>
            <span class="card-sub">新租户自动创建【运营】角色</span></div>
          <div class="table-wrap"><table class="tb"><thead><tr>
            <th>角色</th><th class="num">用户数</th><th class="num">可进入页面</th><th>更新时间</th>
            <th style="width:180px">操作</th></tr></thead>
          <tbody>${rows.length ? rows.map(r => `<tr>
            <td><b>${esc(r.name)}</b>${r.defaultRole ? '<span class="tag tag-info inline-tag">默认</span>' : ''}
              ${r.remark ? `<div class="small muted">${esc(r.remark)}</div>` : ''}</td>
            <td class="num">${r.users}</td>
            <td class="num">${r.pages} 个</td>
            <td class="small muted">${esc(r.updatedAt || '—')}</td>
            <td class="actions">
              <button class="btn btn-sm btn-ghost" data-role-edit="${r.roleId}">编辑</button>
              <button class="btn btn-sm" data-role-del="${r.roleId}">删除</button>
            </td></tr>`).join('')
            : '<tr><td colspan="5"><div class="tb-empty">还没有角色</div></td></tr>'}</tbody></table></div>
        </section></div>`;
    },
    mount(root) {
      if (!rl.catalog) loadRoles().then(() => UI.render()).catch(e => toast('err', e.message));
      const btn = root.querySelector('#r-new');
      if (btn) btn.onclick = () => roleEditor(null);
      root.querySelectorAll('[data-role-edit]').forEach(b => b.onclick = async () => {
        try {
          const d = await CarbonAPI.request('GET', '/roles/' + b.dataset.roleEdit);
          roleEditor(d);
        } catch (e) { toast('err', e.message); }
      });
      root.querySelectorAll('[data-role-del]').forEach(b => b.onclick = async () => {
        const r = rl.rows.find(x => String(x.roleId) === b.dataset.roleDel);
        if (!r) return;
        if (r.defaultRole) { toast('warn', '默认角色不可删除', '可在编辑中调整页面'); return; }
        if (r.users > 0) { toast('warn', '该角色下还有 ' + r.users + ' 名用户', '请先在账号管理中转移'); return; }
        const ok = await confirm({ title: '删除角色', content: `确定删除角色「${esc(r.name)}」？删除后不可恢复。`, okText: '删除', danger: true });
        if (!ok) return;
        try {
          await CarbonAPI.request('DELETE', '/roles/' + r.roleId);
          toast('ok', '角色已删除', r.name);
          await loadRoles(); UI.render();
        } catch (e) { toast('err', e.message); }
      });
    }
  });

  route('/system/tenant', {
    title: '租户信息', group: '系统管理',
    render() {
      const d = lic.data;
      if (!d) {
        return `<div class="page"><div class="page-head"><div><h1 class="page-title">租户信息</h1>
          <p class="page-desc">本企业的授权期、套餐与席位</p></div></div>
          <section class="card"><div class="card-body"><div class="tb-empty">加载中…</div></div></section></div>`;
      }
      const statusMap = {
        active: ['正常', 'tag-ok'], pending: ['未生效', 'tag-info'],
        suspended: ['已停用', 'tag-warn'], expired: ['已到期', 'tag-muted']
      };
      const st = statusMap[d.status] || [d.status, 'tag-muted'];
      const remain = d.remainDays == null ? '长期' : (d.remainDays < 0 ? '已过期 ' + (-d.remainDays) + ' 天' : d.remainDays + ' 天');
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">租户信息</h1>
          <p class="page-desc">授权期由平台管理员设置；如需续期或调整席位，请联系平台管理员</p></div></div>

        <div class="grid grid-4" style="margin-bottom:13px">
          <div class="kpi"><div class="kpi-label">授权状态</div>
            <div class="kpi-value" style="font-size:20px"><span class="tag ${st[1]} dot">${st[0]}</span></div></div>
          <div class="kpi"><div class="kpi-label">剩余天数</div>
            <div class="kpi-value" style="${d.remainDays != null && d.remainDays <= 30 ? 'color:#B45309' : ''}">${d.remainDays == null ? '—' : d.remainDays}<small>天</small></div></div>
          <div class="kpi"><div class="kpi-label">席位</div>
            <div class="kpi-value">${d.seatUsed || 0}<small>/ ${d.seatLimit == null ? '不限' : d.seatLimit}</small></div></div>
          <div class="kpi"><div class="kpi-label">套餐</div>
            <div class="kpi-value" style="font-size:18px">${esc(({ basic: '基础版', standard: '标准版', enterprise: '企业版' })[d.plan] || d.plan || '—')}</div></div>
        </div>

        <section class="card">
          <div class="card-head"><h2 class="card-title">授权详情</h2></div>
          <div class="card-body">
            <div class="grid grid-3" style="gap:12px 22px">
              ${[['企业名称', d.name], ['企业编码', d.code || '—'], ['授权开始', d.licenseStartAt || '—'],
                 ['授权到期', d.licenseEndAt || '长期'], ['剩余天数', remain],
                 ['席位使用', (d.seatUsed || 0) + ' / ' + (d.seatLimit == null ? '不限' : d.seatLimit)]]
                .map(([k, v]) => `<div class="between" style="padding:8px 0;border-bottom:1px dashed var(--line)">
                  <span class="muted small">${k}</span><b>${esc(String(v))}</b></div>`).join('')}
            </div>
            <div class="small muted" style="margin-top:12px">到期后本企业所有账号将无法登录，续期后立即恢复；数据与权限配置不会丢失。</div>
          </div>
        </section></div>`;
    },
    mount() {
      if (!lic.data) loadLicense().then(() => UI.render()).catch(() => {});
    }
  });

  /* ==================== 审计日志（租户内，只读） ==================== */

  const au = {
    page: 1, size: 20, total: 0, rows: [], facets: null,
    entity: '', action: '', actor: '', kw: '', from: '', to: '', loaded: false, seq: 0
  };

  async function loadAudit() {
    const seq = ++au.seq;
    const qs = new URLSearchParams({ page: String(au.page), size: String(au.size) });
    if (au.entity) qs.set('entity', au.entity);
    if (au.action) qs.set('action', au.action);
    if (au.actor) qs.set('actor', au.actor);
    if (au.kw) qs.set('keyword', au.kw);
    if (au.from) qs.set('from', au.from);
    if (au.to) qs.set('to', au.to);
    try {
      if (!au.facets) au.facets = await CarbonAPI.request('GET', '/audit-logs/facets').catch(() => null);
      const r = await CarbonAPI.request('GET', '/audit-logs?' + qs.toString());
      if (seq !== au.seq) return;
      au.rows = r.rows || [];
      au.total = r.total || 0;
      au.loaded = true;
      if (au.page > 1 && !au.rows.length && au.total > 0) { au.page -= 1; return loadAudit(); }
    } catch (e) { toast('err', e.message); }
  }

  const entityName = (code) => {
    const hit = ((au.facets && au.facets.entities) || []).find(x => x.value === code);
    return hit ? hit.label : (code || '—');
  };

  const auditAt = (s) => (s || '').replace('T', ' ').slice(0, 19);

  function auditSummary(before, after, action) {
    const parse = (s) => { try { return s ? JSON.parse(s) : null; } catch (e) { return null; } };
    const b = parse(before), a = parse(after);
    const kb = b ? Object.keys(b) : [];
    const ka = a ? Object.keys(a) : [];
    if (!kb.length && !ka.length) return '—';
    if (!kb.length) return '新增：' + ka.slice(0, 3).join('、') + (ka.length > 3 ? '…' : '');
    if (!ka.length) {
      const head = (action === 'delete' || action === 'remove') ? '删除：' : '变更前：';
      return head + kb.slice(0, 3).join('、') + (kb.length > 3 ? '…' : '');
    }
    const keys = [...new Set([...kb, ...ka])].filter(k => JSON.stringify(b[k]) !== JSON.stringify(a[k]));
    return keys.length ? keys.length + ' 项：' + keys.slice(0, 3).join('、') + (keys.length > 3 ? '…' : '') : '无字段变化';
  }

  const auditPretty = (s) => {
    try { return JSON.stringify(JSON.parse(s), null, 2); } catch (e) { return s || ''; }
  };

  route('/system/audit', {
    title: '审计日志', group: '系统管理',
    render() {
      const ents = (au.facets && au.facets.entities) || [];
      const acts = (au.facets && au.facets.actions) || [];
      return `<div class="page">
        <div class="page-head"><div><h1 class="page-title">审计日志</h1>
          <p class="page-desc">本企业所有写操作的留痕：谁、什么时候、对哪条数据做了什么。审计只追加，不可修改与删除</p></div></div>

        <section class="card" style="margin-bottom:13px"><div class="card-body"><div class="filters">
          <div class="field" style="min-width:236px" data-audrange><label>操作日期</label>
            ${UI.rangePicker({ mode: 'date', from: au.from, to: au.to, ph: '开始日期 ~ 结束日期' })}</div>
          <div class="field"><label>模块</label><select class="select" id="au-entity">
            <option value="">全部模块</option>
            ${ents.map(e => `<option value="${esc(e.value)}" ${e.value === au.entity ? 'selected' : ''}>${esc(e.label)}（${e.count}）</option>`).join('')}
          </select></div>
          <div class="field"><label>动作</label><select class="select" id="au-action">
            <option value="">全部动作</option>
            ${acts.map(a => `<option value="${esc(a.value)}" ${a.value === au.action ? 'selected' : ''}>${esc(a.label)}（${a.count}）</option>`).join('')}
          </select></div>
          <div class="field"><label>操作人</label><input class="input" id="au-actor" placeholder="手机号 / 账号" value="${esc(au.actor)}"></div>
          <div class="field"><label>关键词</label><input class="input" id="au-kw" placeholder="对象 ID / 动作" value="${esc(au.kw)}"></div>
          <div class="field"><label>&nbsp;</label><div class="toolbar">
            <button class="btn btn-primary" id="au-search">查询</button>
            <button class="btn" id="au-reset">重置</button></div></div>
        </div></div></section>

        <section class="card">
          <div class="card-head"><h2 class="card-title">操作记录</h2>
            <span class="card-sub">${au.loaded ? '共 ' + au.total + ' 条' : '加载中…'}</span></div>
          <div class="table-wrap"><table class="tb"><thead><tr>
            <th style="width:150px">时间</th><th>操作人</th><th>模块</th><th>动作</th>
            <th>对象</th><th>变更摘要</th><th style="width:60px"></th></tr></thead>
          <tbody>${au.rows.length ? au.rows.map(r => `<tr>
              <td class="small">${esc(auditAt(r.loggedAt))}</td>
              <td class="small mono">${esc(r.actor || '—')}</td>
              <td class="small">${esc(entityName(r.entity))}</td>
              <td><span class="tag tag-info">${esc(r.actionLabel || r.action)}</span></td>
              <td class="small mono">${esc(r.entityId || '—')}</td>
              <td class="small muted">${esc(auditSummary(r.beforeJson, r.afterJson, r.action))}</td>
              <td><button class="btn btn-sm btn-ghost" data-au-open="${r.id}">详情</button></td>
            </tr>`).join('')
            : `<tr><td colspan="7"><div class="tb-empty">${icon('file')}<div>${au.loaded ? '没有符合条件的操作记录' : '加载中…'}</div></div></td></tr>`}
          </tbody></table></div>
          ${UI.pager(au.total, au.page, au.size)}
        </section></div>`;
    },
    mount(root) {
      if (!au.loaded) loadAudit().then(() => UI.render());
      UI.bindRangePickers(root);
      const readRange = () => {
        const box = root.querySelector('[data-audrange] [data-rp]');
        au.from = box ? box.dataset.from || '' : '';
        au.to = box ? box.dataset.to || '' : '';
      };
      const search = async () => {
        au.entity = root.querySelector('#au-entity').value;
        au.action = root.querySelector('#au-action').value;
        au.actor = root.querySelector('#au-actor').value.trim();
        au.kw = root.querySelector('#au-kw').value.trim();
        readRange();
        au.page = 1;
        await loadAudit();
        UI.render();
      };
      root.querySelector('#au-search').onclick = search;
      root.querySelector('#au-kw').onkeydown = (e) => { if (e.key === 'Enter') search(); };
      root.querySelector('#au-reset').onclick = async () => {
        au.entity = ''; au.action = ''; au.actor = ''; au.kw = ''; au.from = ''; au.to = ''; au.page = 1;
        await loadAudit();
        UI.render();
      };
      UI.bindPager(root, async (n) => { au.page = n; await loadAudit(); UI.render(); });
      root.querySelectorAll('[data-au-open]').forEach(b => b.onclick = () => {
        const r = au.rows.find(x => String(x.id) === String(b.dataset.auOpen));
        if (!r) return;
        modal({
          title: (r.actionLabel || r.action) + ' · ' + entityName(r.entity), width: 720,
          body: `<div class="grid grid-2" style="gap:0 16px;margin-bottom:10px">
            <div class="between" style="padding:6px 0;border-bottom:1px dashed var(--line)">
              <span class="muted small">操作人</span><b class="mono">${esc(r.actor || '—')}</b></div>
            <div class="between" style="padding:6px 0;border-bottom:1px dashed var(--line)">
              <span class="muted small">对象</span><b class="mono">${esc((r.entity || '') + ' #' + (r.entityId || ''))}</b></div>
          </div>
          <div class="small muted" style="margin-bottom:8px">${esc(auditAt(r.loggedAt))}</div>
          ${[['变更前', r.beforeJson], ['变更后', r.afterJson]].map(([t, v]) => `<div style="margin-top:8px">
            <div class="small muted" style="margin-bottom:4px">${t}</div>
            <pre class="mono" style="background:#F7FAF8;border:1px solid var(--line);border-radius:8px;padding:10px;max-height:200px;overflow:auto;font-size:12px;margin:0">${esc(auditPretty(v) || '—')}</pre>
          </div>`).join('')}`,
          foot: '<button class="btn btn-primary" data-close>关闭</button>'
        });
      });
    }
  });
})();
