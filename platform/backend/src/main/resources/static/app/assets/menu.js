/* 信息架构：与竞品 1:1（名称 / 顺序 / 层级 / 路由）；page 为页面编码，与后端 PageCatalog 一致 */
window.MENU = [
  { type: 'page', name: '首页', path: '/home', icon: 'gauge', page: 'home' },
  { type: 'group', name: '企业管理', children: [
    { name: '企业信息', path: '/enterprise/info', page: 'enterprise.info' },
    { name: '集团汇总', path: '/group/overview', page: 'group.overview' }] },
  { type: 'group', name: '预算管理', children: [
    { name: '碳排放配额', path: '/carbon-emission/quota/list', page: 'quota.list' }] },
  { type: 'group', name: '产值管理', children: [
    { name: '产品列表', path: '/carbon-product/product/list', page: 'product.list' },
    { name: '产品产值', path: '/carbon-output/production-value/list', page: 'production.value' },
    { name: '生产总值', path: '/carbon-output/total-value/list', page: 'total.value' }] },
  { type: 'group', name: '碳排放核算', children: [
    { name: '碳排放核算', path: '/carbon-computation/calculation-complete/list', page: 'accounting.list' }] },
  { type: 'group', name: '碳足迹管理', children: [
    { name: '碳足迹核算', path: '/carbon-footprint/product-footprint/list', page: 'footprint.list' },
    { name: '碳足迹分析', path: '/carbon-footprint/product-footprint-analysis/index', page: 'footprint.analysis' },
    { name: '供应链碳管理', path: '/carbon-footprint/supplier-footprint/list', page: 'supplier.list' },
    { name: '供应链碳管理分析', path: '/carbon-footprint/supplier-footprint-analysis/index', page: 'supplier.analysis' }] },
  { type: 'group', name: '碳资产管理', children: [
    { name: '碳配额资产管理', path: '/carbon-asset/asset/list', page: 'asset.list' },
    { name: '信用资产管理', path: '/carbon-asset/ccer/list', page: 'ccer.list' },
    { name: '交易管理', path: '/carbon-asset/trade/list', page: 'trade.list' }] },
  { type: 'group', name: '碳核查支撑', children: [
    { name: '原始凭证管理', path: '/carbon-verify/evidence/list', page: 'evidence.list' },
    { name: '第三方报告管理', path: '/carbon-verify/report/list', page: 'third.report' }] },
  { type: 'group', name: '报告查询', children: [
    { name: '碳排放报告', path: '/carbon-report/calculation-report/query', page: 'report.carbon' },
    { name: '能源利用报告', path: '/carbon-report/energy-utilization-report/query', page: 'report.energy' },
    { name: '产品碳足迹报告', path: '/carbon-report/product-footprint-report/query', page: 'report.footprint' }] },
  { type: 'group', name: '能碳分析', children: [
    { name: '能耗查询', path: '/carbon-analysis/energy-consumption-query/index', page: 'analysis.energy' },
    { name: '能源消费分析与策略推荐', path: '/carbon-analysis/energy-strategy-recommend/index', page: 'analysis.strategy' },
    { name: '能效对标', path: '/carbon-analysis/energy-efficiency-benchmarking/index', page: 'analysis.benchmark' },
    { name: '碳排放查询', path: '/carbon-analysis/energy-usage-management/index', page: 'analysis.carbon' },
    { name: '碳配额核算', path: '/carbon-analysis/carbon-quota-calculation/index', page: 'analysis.quota' },
    { name: '能流分析', path: '/carbon-analysis/energy-structure-analysis/index', page: 'analysis.flow' }] },
  { type: 'group', name: '在线监测', children: [] },
  { type: 'group', name: '设备设施', children: [
    { name: '设施管理', path: '/carbon-facility/facility/list', page: 'facility.list' },
    { name: '设备管理', path: '/carbon-facility/device/list', page: 'device.list' },
    { name: '设备数据', path: '/carbon-facility/device-data/list', page: 'device.data' }] },
  { type: 'group', name: '预警管理', children: [
    { name: '事件预警', path: '/common-warning/event/list', page: 'alert.event' },
    { name: '预警设置', path: '/common-warning/setting/list', page: 'alert.setting' },
    { name: '预警记录', path: '/common-warning/record/list', page: 'alert.record' }] },
  { type: 'group', name: '节能管理', children: [
    { name: '节能改造项目', path: '/carbon-economize/renovation-project/list', page: 'retrofit.list' },
    { name: '能源管理情况', path: '/carbon-economize/management-situation/list', page: 'energy.mgmt' }] },
  { type: 'group', name: '系统管理', adminOnly: true, children: [
    { name: '账号管理', path: '/system/users' },
    { name: '角色管理', path: '/system/roles' },
    { name: '租户信息', path: '/system/tenant' },
    { name: '审计日志', path: '/system/audit' }] },
  { type: 'group', name: '平台管理', platform: true, children: [
    { name: '平台概览', path: '/platform/overview' },
    { name: '租户管理', path: '/platform/tenants' },
    { name: '平台审计', path: '/platform/audit' }] },
];
