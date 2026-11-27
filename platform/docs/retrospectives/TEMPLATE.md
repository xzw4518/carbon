# 复盘模板（M18）

> 用法：复制本文件为 `RETRO-YYYYMMDD-NN.md`，48 小时内完成；无行动项视为未完成。
> 场规：对事不对人；先事实后观点；追问“哪条机制漏了”；最多 3 条根因、3 条行动。

```yaml
retro_id: RETRO-YYYYMMDD-01
scope: 一句话说明复盘对象
trigger: 事故 / 迭代 / 门禁连续 FAIL / 决策推翻 / 用户投诉
facilitator: 席位（不得为直接 owner）
scribe: 席位
critic: 席位
facts:
  started_at: ""
  detected_at: ""
  recovered_at: ""
  impact: "影响面：用户/数据/时长"
  gate_fail: 0
  incidents: 1
timeline:
  - "HH:mm 事件"
what_worked: []
what_didnt: []
root_causes:
  - type: 需求口径 / 设计实现 / 验证门禁 / 协作流程
    why: "5 Why 结论，必须指到产物或规则"
actions:
  - id: ACT-...
    type: 规则 / 门禁 / 模板 / 监控
    action: "改哪个文件、加哪条断言"
    owner: "席位"
    due: "YYYY-MM-DD"
    acceptance: "可复跑/可检查"
    status: open
```

## 1. 事实与时间线（不带评价）

| 时间 | 事件 | 证据 |
|---|---|---|
|  |  | 日志/截图/命令输出 |

## 2. 影响面

- 用户：
- 数据：
- 发布/回滚：

## 3. 5 Why（最多 3 条根因）

1. 为什么没被拦住？
2. 为什么没被测出？
3. 为什么流程允许？
4. 为什么经验没沉淀？

## 4. 行动项（进 `actions.md` 台账）

| ID | 类型 | 动作 | Owner | 截止 | 验收 | 状态 |
|---|---|---|---|---|---|---|
| ACT-... | 规则/门禁/模板/监控 |  |  |  |  | open |

## 5. 沉淀

- 更新了哪条规则（AGENTS.md）：
- 新增/修改了哪个门禁：
- 更新了哪个模板/问题库：
