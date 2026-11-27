# 性能预算（M10）

> 基线：2026-11-25 开发机（Windows / JDK17 / MySQL 8.0.28 本地 3307）。
> 采集：`bash platform/backend/tools/perf.sh`，日志落在 `platform/backend/target/inspect/perf-<日期>.log`。
> 变更后（新增接口、首页改版、引入大依赖）必须重跑；连续两次超预算即阻断发布。

## 1. 预算与实测

| 指标 | 预算 | 基线实测 | 采集方式 |
|---|---|---|---|
| `/api/bootstrap` 原始体积 | ≤ 60 KB | **30 KB** | `curl size_download` |
| `/api/bootstrap` gzip | ≤ 20 KB | **4 KB** | `Accept-Encoding: gzip` |
| 关键接口中位耗时 | ≤ 300 ms | bootstrap 78 / 列表 6 / overview 26 / 分析 16 / 预警 7 | 5 次取中位 |
| 前端产物总量 | ≤ 1024 KB | **404 KB** | index.html + assets 字节和 |
| 首屏 FCP | ≤ 1200 ms | **344 ms** | Playwright + Paint Timing |
| 首屏 LCP | ≤ 2000 ms | **344 ms** | PerformanceObserver |
| 首屏请求数（登录后 3s） | ≤ 30 | **16** | Playwright request 计数 |
| 首屏传输量 | ≤ 300 KB | **111 KB** | Resource Timing `transferSize` |
| 首页 DOM 节点 | ≤ 1200 | **386** | `document.querySelectorAll('*')` |
| 控制台错误 | 0 | **0**（未登录会话探测 401 不计） | Playwright console |

## 2. 预算依据

- **bootstrap ≤ 60KB**：当前 30KB，留一倍余量；超过 60KB 说明又有台账被塞回快照（9 类流水已迁服务端分页）。
- **接口 ≤ 300ms**：本地基线 < 100ms，留 3 倍余量覆盖生产网络与冷启动；超过说明出现 N+1 或全表扫描。
- **FCP/LCP**：内网后台目标 1.2s / 2s（Google “良好”阈值 1.8s / 2.5s 的收紧版）。
- **请求 ≤ 30**：首页只应有 bootstrap + 字典/图表 + 静态资源；超过说明页面在逐个拉列表。
- **产物 ≤ 1MB**：当前 404KB，主要是 CSS 与 SVG 图标；不允许引入前端框架运行时。

## 3. 触发的处置

| 超预算项 | 先查 |
|---|---|
| bootstrap 体积 | `BootstrapService` 是否又 `db.put` 了列表数组 |
| 接口耗时 | 慢查询日志、是否缺索引、是否循环查库 |
| FCP/LCP | 是否有阻塞脚本、字体、大图 |
| 请求数 | 是否有页面在 `mount` 里串行请求多个接口 |
| 产物体积 | 是否新增未压缩资源或重复拷贝 |

## 4. 局限制约

- 基线是**开发机 localhost**，不含公网 RTT；生产验收需在同一地域压测后另行登记。
- 首屏指标依赖本机 Chrome 与 `playwright-core`；无浏览器环境时脚本自动跳过，不算通过。
- 未做并发压测（JMeter/k6）；当前预算只约束单用户交互体验，容量规划见 `deploy.md`。
