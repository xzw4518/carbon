/* 账号安全：登录后改密提醒 + 修改密码弹窗（对齐竞品字段与校验文案） */
(function () {
  const { modal, toast, $ } = UI;
  const RULE = '密码长度至少 12 位，需同时包含大写字母、小写字母、数字和符号（如 @#$%）';
  const okPwd = (v) => v.length >= 12
    && /[A-Z]/.test(v) && /[a-z]/.test(v) && /\d/.test(v) && /[^A-Za-z0-9]/.test(v);

  function openPwd(opts) {
    const forced = !!(opts && opts.forced);
    const onDone = opts && opts.onDone;
    let finished = false;
    modal({
      title: forced ? '首次登录：请修改初始密码' : '', width: 470,
      body: `<div style="background:${forced ? '#fff1f0' : '#fffbe6'};border:1px solid ${forced ? '#ffccc7' : '#ffe58f'};border-radius:8px;padding:8px 10px;font-size:12.5px;color:${forced ? '#a8071a' : '#ad6800'};margin:0 24px 14px 0">
          <b>!</b> ${forced ? '当前使用的是管理员下发的临时密码，修改后才能进入系统。' : '根据《网络安全法》和《数据安全法》要求，需要定期修改密码保障数据安全！'}</div>
        <form id="pwd-form" novalidate>
          <div class="field" data-f="old" style="margin-bottom:12px"><label class="req">原密码</label>
            <input class="input" type="password" name="old" placeholder="请输入原密码" autocomplete="current-password"><div class="err-msg"></div></div>
          <div class="field" data-f="next" style="margin-bottom:12px"><label class="req">新密码</label>
            <input class="input" type="password" name="next" placeholder="请输入新密码" autocomplete="new-password">
            <div class="small muted" style="margin-top:4px">${RULE}</div><div class="err-msg"></div></div>
          <div class="field" data-f="again" style="margin-bottom:16px"><label class="req">确认密码</label>
            <input class="input" type="password" name="again" placeholder="请输入确认密码" autocomplete="new-password">
            <div class="small muted" style="margin-top:4px">${RULE}</div><div class="err-msg"></div></div>
          <div style="text-align:center"><button type="button" class="btn btn-primary" id="pwd-save" style="min-width:104px">修改密码</button>
            ${forced ? '' : ''}</div>
        </form>`,
      onMount(wrap, close) {
        const box = wrap.querySelector('.modal');
        if (box) box.style.position = 'relative';
        const head = wrap.querySelector('.modal-head');
        if (head) { head.style.cssText = 'position:absolute;right:10px;top:10px;border:0;padding:0;background:transparent;z-index:2'; head.querySelector('.modal-title').style.display = 'none'; }
        const form = wrap.querySelector('#pwd-form');
        const setE = (n, m) => {
          const f = form.querySelector(`[data-f="${n}"]`); if (!f) return;
          f.classList.toggle('error', !!m);
          f.querySelector('.err-msg').textContent = m || '';
          f.querySelector('.err-msg').style.color = m ? 'var(--danger-600)' : '';
          const hint = f.querySelector('.small.muted'); if (hint) hint.style.display = m ? 'none' : '';
        };
        form.querySelectorAll('input').forEach(i => i.oninput = () => setE(i.name, ''));
        form.querySelector('#pwd-save').onclick = async () => {
          const d = Object.fromEntries(new FormData(form));
          let bad = false;
          if (!d.old) { setE('old', '请输入原密码'); bad = true; }
          if (!okPwd(d.next)) { setE('next', RULE); bad = true; }
          if (!okPwd(d.again)) { setE('again', RULE); bad = true; }
          if (bad) { toast('err', '参数验证错误，请仔细填写表单数据!'); return; }
          if (d.next !== d.again) { toast('err', '新密码与确认密码不一致'); return; }
          const btn = form.querySelector('#pwd-save');
          btn.disabled = true; btn.textContent = '提交中…';
          try {
            await CarbonAPI.request('POST', '/auth/password/change',
              { oldPassword: d.old, newPassword: d.next });
            sessionStorage.setItem('cb_pwd_prompt', '1');
            finished = true;
            close();
            if (forced) {
              toast('ok', '密码已修改', '请用新密码重新登录');
              if (onDone) setTimeout(onDone, 900);
              return;
            }
            toast('ok', '密码已修改', '为保证安全，请重新登录');
            setTimeout(() => CarbonAPI.logout(), 900);
          } catch (e) {
            btn.disabled = false; btn.textContent = '修改密码';
            setE('next', e.message);
            toast('err', e.message);
          }
        };
      },
      onClose() {
        if (forced && !finished) {
          // 不允许跳过：关掉后重新提示（改密成功后不再重开）
          setTimeout(() => openPwd(opts), 150);
          return;
        }
        sessionStorage.setItem('cb_pwd_prompt', '1');
      }
    });
  }
  window.UI.openPwd = openPwd;

  // 改密入口只在用户菜单；首次登录由 api-client 按 mustChangePassword 强制弹窗，
  // 密码临近到期由 warnPasswordExpiry 提示，避免每次新会话都无条件打断首页。
})();
