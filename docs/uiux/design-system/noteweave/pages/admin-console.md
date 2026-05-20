# Admin Console Override

本页覆盖 `MASTER.md`。

## 1. Page Intent

Admin 是运维与诊断界面，核心目标是：

- 快速发现异常
- 快速定位任务与组件问题
- 快速执行 retry / cancel / inspect

## 2. Tone

- 比工作台更克制
- 更高信息密度
- 更少装饰
- 更强表格与状态语义

## 3. Layout

```text
Header
Metric Row
Filter Toolbar
Primary Table
Right Drawer / Detail Panel
```

## 4. Charts

根据 `ui-ux-pro-max` 检索：

- 对比型数据优先 `Bar Chart`
- 多指标概览谨慎使用 `Radar`
- 所有图表都要附数值或表格补充

因此：

- 健康态趋势图优先条形/折线
- Eval 结果优先表格 + 水平条
- 避免只靠炫酷热力图传达信息

## 5. Avoid

- 不要把管理后台做成深色炫技面板
- 不要让失败原因藏在多层展开之后
- 不要把关键操作和危险操作做成同色
