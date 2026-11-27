/* 顶栏：折叠菜单 / 首页 / 网站设置 */
(function () {
  const { $, toast, go, drawer } = UI;

  const THEMES = {
    green: { 900: '#07241D', 800: '#0A3128', 700: '#0C5A44', 600: '#0F7A5A', 500: '#12A374', 100: '#D8F2E8', 50: '#EFFAF5' },
    blue: { 900: '#0b1e3a', 800: '#12294d', 700: '#1e3a8a', 600: '#2563eb', 500: '#3b82f6', 100: '#dbeafe', 50: '#eff6ff' },
    violet: { 900: '#22103f', 800: '#2e1655', 700: '#4c1d95', 600: '#7c3aed', 500: '#8b5cf6', 100: '#ede9fe', 50: '#f5f3ff' },
    amber: { 900: '#2b1a05', 800: '#3b2508', 700: '#78350f', 600: '#b45309', 500: '#d97706', 100: '#fef3c7', 50: '#fffbeb' },
  };
  const applyTheme = (k) => {
    const t = THEMES[k] || THEMES.green;
    const root = document.documentElement.style;
    Object.keys(t).forEach(n => root.setProperty('--brand-' + n, t[n]));
  };
  const applyRadius = (v) => {
    const root = document.documentElement.style;
    root.setProperty('--radius', v + 'px');
    root.setProperty('--radius-sm', Math.max(0, Number(v) - 4) + 'px');
  };
  const applySide = (v) => document.documentElement.style.setProperty('--side-w', v + 'px');

  const btnCollapse = $('#btn-collapse');
  if (btnCollapse) btnCollapse.onclick = () => {
    const app = document.querySelector('.app');
    app.classList.toggle('side-collapsed');
    toast('ok', app.classList.contains('side-collapsed') ? '已折叠菜单' : '已展开菜单');
  };
  const btnHome = $('#btn-home');
  if (btnHome) btnHome.onclick = () => go('/home');

  const btnSettings = $('#btn-settings');
  if (btnSettings) btnSettings.onclick = () => drawer({
    title: '网站设置',
    body: '<div class="stack" style="gap:16px">'
      + '<div><div class="small" style="font-weight:650;margin-bottom:8px">主题颜色</div><div class="row" style="gap:10px" id="st-themes">'
      + Object.keys(THEMES).map((k, i) => '<button data-theme="' + k + '" aria-label="' + k + '" style="width:28px;height:28px;border-radius:8px;border:2px solid ' + (i === 0 ? 'var(--ink-900)' : 'transparent') + ';cursor:pointer;background:' + THEMES[k][600] + '"></button>').join('')
      + '</div></div>'
      + '<div><div class="small" style="font-weight:650;margin-bottom:6px">页面圆角 <span class="muted" id="rd-val">12 px</span></div>'
      + '<input type="range" min="0" max="20" value="12" id="rd" style="width:100%"></div>'
      + '<div><div class="small" style="font-weight:650;margin-bottom:6px">菜单宽度 <span class="muted" id="sd-val">240 px</span></div>'
      + '<input type="range" min="200" max="300" step="4" value="240" id="sd" style="width:100%"></div>'
      + '<div class="between"><span class="small">标签页</span><button class="switch on" id="sw-tags" aria-label="标签页显示"></button></div>'
      + '<div class="between"><span class="small">页脚</span><button class="switch on" id="sw-foot" aria-label="页脚显示"></button></div>'
      + '<div class="between"><span class="small">水印</span><button class="switch" id="sw-wm" aria-label="水印显示"></button></div>'
      + '<div class="hr"></div><button class="btn" id="st-reset">恢复默认配置</button></div>',
    onMount(el) {
      const tag = el.querySelector('#sw-tags'), foot = el.querySelector('#sw-foot'), wm = el.querySelector('#sw-wm');
      [tag, foot, wm].forEach(x => x.onclick = () => { x.classList.toggle('on'); sync(); });
      el.querySelectorAll('[data-theme]').forEach(b => b.onclick = () => {
        el.querySelectorAll('[data-theme]').forEach(x => x.style.borderColor = 'transparent');
        b.style.borderColor = 'var(--ink-900)';
        applyTheme(b.dataset.theme);
      });
      el.querySelector('#rd').oninput = (e) => { applyRadius(e.target.value); el.querySelector('#rd-val').textContent = e.target.value + ' px'; };
      el.querySelector('#sd').oninput = (e) => { applySide(e.target.value); el.querySelector('#sd-val').textContent = e.target.value + ' px'; };
      function sync() {
        document.body.classList.toggle('no-tags', !tag.classList.contains('on'));
        document.body.classList.toggle('no-foot', !foot.classList.contains('on'));
        document.body.classList.toggle('wm', wm.classList.contains('on'));
      }
      el.querySelector('#st-reset').onclick = () => {
        applyTheme('green'); applyRadius(12); applySide(240);
        document.body.classList.remove('no-tags', 'no-foot', 'wm');
        el.querySelectorAll('[data-theme]').forEach(x => x.style.borderColor = 'transparent');
        const first = el.querySelector('[data-theme="green"]');
        if (first) first.style.borderColor = 'var(--ink-900)';
        el.querySelector('#rd').value = 12; el.querySelector('#rd-val').textContent = '12 px';
        el.querySelector('#sd').value = 240; el.querySelector('#sd-val').textContent = '240 px';
        tag.classList.add('on'); foot.classList.add('on'); wm.classList.remove('on');
        toast('ok', '已恢复默认配置');
      };
    }
  });
})();
