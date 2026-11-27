# 任务卡模板（M19）

> 复制到任务系统或对话开头。缺任一必填项不排期；`write_scopes` 之外的文件不得修改。

```yaml
task_id: T-YYYYMMDD-NN
owner: A1
deliverable: "一句话说明产出（文件 + 行为变化）"
write_scopes:
  - "允许修改的目录/文件前缀"
acceptance:
  - "可直接执行的命令"
  - "期望输出（数字/状态码/文本）"
blocked_by: []
d_level: D1          # D0/D1/D2/D3
pack_session: false  # 只有汇总会话为 true
notes: "假设与风险；什么证据会推翻"
```

## 交付时必须附

| 项 | 内容 |
|---|---|
| artifact | 改动文件路径 |
| decision | ≤3 条关键决定 |
| assumptions | 假设与待确认 |
| next | 1–3 条后续 |
| verification | 命令 + 实际输出 |
