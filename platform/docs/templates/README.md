# 模板库索引（M20）

> 原则：模板是**可复制的最小集**，不是知识库。新项目按档位选子集，不要整套照搬。
> 每个模板只有一个维护位置；其他文档引用，不复制。

## 1. 模板清单

| 模板 | 位置 | 用途 | 最小字段 |
|---|---|---|---|
| 需求卡（三段式） | `carbonbal-web/docs/需求卡模板.md` | D2 变更先填卡再开工 | 方案 / 字段设计 / 执行清单 / 不做清单 / AC |
| 任务卡 | `platform/docs/templates/TASK-CARD.md` | 并行任务边界 | write_scopes / 验收 / blocked_by / pack_session |
| 交接单 | `platform/docs/templates/HANDOFF.md` | 任务会话收尾 | artifact / decision / assumptions / next / verification |
| 上线与回滚 | `platform/docs/release-and-rollback.md` | 发布与回退 | 产物指纹 / 14 项验证 / 台账 / 迁移登记 |
| 复盘 | `platform/docs/retrospectives/TEMPLATE.md` | 事故/迭代复盘 | 时间线 / 5 Why / 行动项四列 |
| 数据字典 | `carbonbal-web/docs/字段与口径.md` | 字段与口径 | 表.列 / 谁写 / 谁读 / 口径 / 真相源 |
| 接口契约 | `platform/docs/architecture.md` §4 + `error-codes.md` | 前后端边界 | 路径 / 方法 / 信封 / 错误码 |
| 权限矩阵 | `platform/docs/permissions.md` | 角色能力 | 角色 × 能力 × 归属 × 二次确认 |
| 状态机 | `platform/docs/state-machines.md` | 状态迁移 | 状态 / 事件 / 触发者 / 副作用 / 审计 |
| 门禁清单 | `platform/README.md` §10 | 交付验证 | L0–L4 命令与阈值 |
| 问题库 | `platform/docs/problem-library.md` | 新项目检查表 | 现象 / 根因 / 规则 / 门禁 |
| 档位选型 | `carbonbal-web/AGENTS.md` §2 | 按项目底子选机制 | 启用 / 后置 / 受限项 |

## 2. 新项目启动清单

**第 1 天**

- [ ] `AGENTS.md`：项目事实、目录、六种必须停、交接五件套
- [ ] 需求卡模板 + 数据字典（哪怕只有 10 个字段）
- [ ] 上线清单（产物、备份、回滚）

**第 1 周**

- [ ] L0 静态门禁 + L1 接口冒烟 + L2 UI 门禁
- [ ] 错误码表与统一信封
- [ ] 问题库过一遍，命中项先补规则

**首个里程碑**

- [ ] 四向矩阵（P0 条目）
- [ ] 权限矩阵 + 状态机五列表
- [ ] 发布台账 + 回滚演练
- [ ] 一次复盘（哪怕 15 分钟）

## 3. 维护规则

1. 模板改动必须同步“使用它的文档”，不允许两套口径。
2. 数字（断言数、路由数、接口数）只在一处维护，其余引用；`tools/check-docs.sh` 检查过期数字。
3. 90 天未使用的模板标记 `stale`，要么删、要么说明保留原因。
